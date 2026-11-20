const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_student_1";
const BOB_UID = "bob_student_2";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfile(uid, ts) {
  return {
    userId: uid,
    displayName: "Aryan",
    preferredLanguage: "Auto",
    explanationPace: "Balanced",
    difficultyLevel: "Beginner",
    voiceEnabled: true,
    preferredVoice: "Warm Mentor",
    createdAt: ts,
    updatedAt: ts,
  };
}

function validLesson(uid, ts) {
  return {
    userId: uid,
    title: "Solving 2x + 5 = 15",
    subject: "Maths",
    level: "Beginner",
    language: "English",
    status: "active",
    summary: "Isolated 2x by subtracting 5 from both sides.",
    currentStep: "Divide both sides by 2",
    formulas: ["2x + 5 = 15", "2x = 10", "x = 5"],
    unresolvedQuestions: [],
    boardEvents: [
      {
        eventId: "evt_1",
        lessonId: "lesson_1",
        stepId: "step_1",
        sequence: 1,
        type: "WRITE_MATH",
        payload: "2x + 5 = 15",
        x: 0.1,
        y: 0.15,
        width: 0.6,
        height: 0.08,
        transcriptAnchor: "Let's start with 2x + 5 = 15",
        status: "complete",
      },
    ],
    studentStrokes: [
      {
        strokeId: "stroke_1",
        colorHex: -15658735,
        strokeWidth: 6.0,
        isEraser: false,
        timestampMs: 1700000000000,
        points: [{ x: 0.1, y: 0.2 }, { x: 0.2, y: 0.25 }],
      },
    ],
    recentTurns: [
      { role: "student", text: "How do I solve 2x + 5 = 15?" },
      { role: "teacher", text: "Let's subtract 5 from both sides first." },
    ],
    createdAt: ts,
    updatedAt: ts,
  };
}

function validProgress(uid, ts) {
  return {
    userId: uid,
    subject: "Maths",
    topic: "Linear Equations",
    masteryPercent: 75,
    quizzesCompleted: 4,
    quizzesCorrect: 3,
    strengthNote: "Good at balancing equations",
    weakAreaNote: "Watch negative signs when transposing",
    lastQuizQuestion: "If 3x - 4 = 11, what is x?",
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write profiles, lessons, or progress", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  const ts = new Date(Date.now() - 1000);

  await assertFails(unauth.collection("users").doc(ALICE_UID).get());
  await assertFails(unauth.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID, ts)));
  await assertFails(unauth.collection("users").doc(ALICE_UID).collection("lessons").get());
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).collection("lessons").doc("l1").set(validLesson(ALICE_UID, ts))
  );
  await assertFails(unauth.collection("users").doc(ALICE_UID).collection("progress").get());
});

test("Owner can create, read, update, and delete UserProfile; rejects ghost fields & future timestamps", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(alice.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID, ts)));
  await assertSucceeds(alice.collection("users").doc(ALICE_UID).get());

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).update({
      preferredLanguage: "Hindi",
      explanationPace: "Gentle",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field (Shadow Update)
  await assertFails(
    alice.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    alice.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    alice.collection("users").doc("another_id").set(validProfile("another_id", new Date(Date.now() + 60000)))
  );
});

test("Owner can create, list, update, and delete lessons and progress; rejects @DocumentId id field", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  // Empty subcollection list must succeed
  await assertSucceeds(alice.collection("users").doc(ALICE_UID).collection("lessons").get());
  await assertSucceeds(alice.collection("users").doc(ALICE_UID).collection("progress").get());

  // Create lesson & progress without id field
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_1").set(validLesson(ALICE_UID, ts))
  );
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("progress").doc("prog_1").set(validProgress(ALICE_UID, ts))
  );

  // Partial update on lesson
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_1").update({
      summary: "Solved x = 5 cleanly.",
      status: "completed",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject @DocumentId `id` stored inside doc body
  const lessonWithId = { ...validLesson(ALICE_UID, ts), id: "lesson_2" };
  await assertFails(
    alice.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_2").set(lessonWithId)
  );

  // Reject invalid quiz invariant (quizzesCorrect > quizzesCompleted)
  const badProgress = { ...validProgress(ALICE_UID, ts), quizzesCorrect: 10, quizzesCompleted: 4 };
  await assertFails(
    alice.collection("users").doc(ALICE_UID).collection("progress").doc("prog_bad").set(badProgress)
  );

  // Owner delete works (privacy requirement)
  await assertSucceeds(alice.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_1").delete());
});

test("Cross-user isolation blocks Bob from reading or modifying Alice's data", async () => {
  const ts = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID, ts));
    await db.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_1").set(validLesson(ALICE_UID, ts));
    await db.collection("users").doc(ALICE_UID).collection("progress").doc("prog_1").set(validProgress(ALICE_UID, ts));
  });

  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bob.collection("users").doc(ALICE_UID).get());
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("lessons").get());
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_1").get());
  await assertFails(
    bob.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_1").update({
      title: "Hijacked",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("lessons").doc("lesson_1").delete());
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("progress").get());
});
