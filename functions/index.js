const functions = require("firebase-functions");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");

admin.initializeApp();

// Configure the email transport for deletePastAppointments
// TEMPORARY: Hardcoded for local testing. REMOVE BEFORE DEPLOYMENT!
// For production, use Firebase environment configuration (functions.config())
// functions.config().gmail.email and functions.config().gmail.password
const gmailEmail = "my.meritxell.app@gmail.com";
const gmailPassword = "abcdefghijklmnop";

const transporter = nodemailer.createTransport({
  service: "gmail",
  auth: {
    user: gmailEmail,
    pass: gmailPassword,
  },
});

/**
 * Cloud Function: deletePastAppointments
 * Triggered by a Pub/Sub schedule (every 1 hour).
 * Deletes appointments that are older than 24 hours and have a 'pending' status.
 * Sends an email notification to the user before deletion and logs the deleted appointment.
 */
exports.deletePastAppointments = functions.pubsub
  .schedule("every 1 hours") // Runs every 1 hour
  .onRun(async (context) => {
    const now = admin.firestore.Timestamp.now();
    const db = admin.firestore(); // Use db here for consistency
    const appointmentsRef = db.collection("appointments");
    const logRef = db.collection("deletedAppointmentsLog");

    try {
      const cutoff = admin.firestore.Timestamp.fromMillis(
        now.toMillis() - 24 * 60 * 60 * 1000 // 24 hours in milliseconds
      );

      const snapshot = await appointmentsRef
        .where("scheduledTimestamp", "<", cutoff)
        .where("status", "==", "pending")
        .get();

      if (snapshot.empty) {
        console.log("No expired pending appointments to delete.");
        return null;
      }

      const batch = db.batch();

      for (const doc of snapshot.docs) {
        const data = doc.data();

        if (data.email) {
          const mailOptions = {
            from: `MERITXELL <${gmailEmail}>`,
            to: data.email,
            subject: "Expired Appointment Removed - MERITXELL Foundation",
            text: `
Dear ${data.username || 'User'},

Your appointment scheduled for ${data.date || 'an unspecified date'} at ${data.time || 'an unspecified time'} has expired and was not confirmed in time.

You may schedule a new appointment via the MERITXELL app.

Thank you.
            `,
          };

          try {
            await transporter.sendMail(mailOptions);
            console.log("Email sent to", data.email);
          } catch (emailError) {
            console.error("Failed to send email to", data.email, emailError);
          }
        }

        await logRef.add({
          deletedAt: now,
          originalId: doc.id,
          ...data,
        });
        console.log(`Logged deletion for appointment ID: ${doc.id}`);

        batch.delete(doc.ref);
      }

      await batch.commit();
      console.log(`Successfully deleted ${snapshot.size} expired pending appointments and logged them.`);

      return null;
    } catch (error) {
      console.error("Error deleting expired appointments:", error);
      return null;
    }
  });


/**
 * Helper function to delete documents in batches from a given query.
 * @param {admin.firestore.Query} query The Firestore query for documents to delete.
 * @param {number} batchSize The maximum number of documents to delete in a single batch operation.
 * @return {Promise<void>} A promise that resolves when all documents matching the query are deleted.
 */
async function deleteQueryBatch(query, batchSize) {
    const snapshot = await query.limit(batchSize).get();

    if (snapshot.size === 0) {
        return; // No documents to delete, or all deleted.
    }

    const batch = admin.firestore().batch();
    snapshot.docs.forEach(doc => {
        batch.delete(doc.ref);
    });
    await batch.commit();

    // If we deleted the maximum number of documents for a batch, there might be more.
    // Recurse to delete the next batch.
    if (snapshot.size === batchSize) {
        return deleteQueryBatch(query, batchSize);
    }
}

/**
 * Helper function to copy documents from a source collection to a destination collection.
 * This function copies documents at the top level of the source collection reference.
 * It does not recursively copy subcollections of the documents being copied.
 * @param {admin.firestore.CollectionReference} sourceCollectionRef The reference to the source collection.
 * @param {admin.firestore.CollectionReference} destinationCollectionRef The reference to the destination collection.
 * @param {number} batchSize The maximum number of documents to copy in a single batch operation.
 * @returns {Promise<void>} A promise that resolves when all documents are copied.
 */
async function copyCollection(sourceCollectionRef, destinationCollectionRef, batchSize) {
    let lastDoc = null;
    let hasMore = true;

    while (hasMore) {
        let query = sourceCollectionRef.orderBy(admin.firestore.FieldPath.documentId()).limit(batchSize);
        if (lastDoc) {
            query = query.startAfter(lastDoc);
        }

        const snapshot = await query.get();
        if (snapshot.empty) {
            hasMore = false;
            break;
        }

        const batch = admin.firestore().batch();
        snapshot.docs.forEach(doc => {
            batch.set(destinationCollectionRef.doc(doc.id), doc.data());
        });
        await batch.commit();

        lastDoc = snapshot.docs[snapshot.docs.length - 1];
        hasMore = snapshot.size === batchSize;
    }
}


/**
 * HTTPS Callable Cloud Function to archive a user's completed application
 * and prepare their account for a new application cycle.
 *
 * This function assumes active application data (e.g., `adopt_progress` map,
 * `stepX_documents`, `stepX_uploads`, `comments`) is stored under:
 * - `adoption_progress/{userId}/{currentApplicationId}`
 * - `user_submissions_status/{userId}/{currentApplicationId}`
 *
 * You MUST ensure your Android app writes data to these paths.
 */
exports.archiveUserApplication = functions.https.onCall(async (data, context) => {
    // 1. Authentication Check: Ensure the function is called by an authenticated user.
    if (!context.auth) {
        throw new functions.https.HttpsError(
            'unauthenticated',
            'The function must be called while authenticated.'
        );
    }

    const userId = context.auth.uid;
    // The ID of the application instance to be archived, e.g., "ADOPT-ABC123DEF"
    const currentApplicationIdToArchive = data.applicationId;

    // 2. Input Validation: Ensure the application ID is provided.
    if (!currentApplicationIdToArchive) {
        throw new functions.https.HttpsError(
            'invalid-argument',
            'Application ID is required to archive.'
        );
    }

    functions.logger.log(`Archiving application ${currentApplicationIdToArchive} for user ${userId}`);

    const db = admin.firestore(); // Get Firestore instance
    // Reference to the user's main application metadata document (e.g., useradopt/{userId})
    const userAdoptDocRef = db.collection('useradopt').doc(userId);

    // References for archiving in history (user_history/{userId}/applications/{applicationId})
    const historyApplicationsCollectionRef = db.collection('user_history').doc(userId).collection('applications');
    const historyAppDocRef = historyApplicationsCollectionRef.doc(currentApplicationIdToArchive); // Use the original ID for history

    // Define batch size for document operations
    const batchSize = 50; // You can adjust this

    try {
        // 3. Verify the current application ID in useradopt matches what's being archived.
        const userAdoptSnapshot = await userAdoptDocRef.get();
        if (!userAdoptSnapshot.exists || userAdoptSnapshot.data().currentApplicationId !== currentApplicationIdToArchive) {
            throw new functions.https.HttpsError(
                'failed-precondition',
                'The provided application ID does not match the user\'s current active application or no active application found. Archiving aborted.'
            );
        }

        // --- Check if already archived ---
        const existingHistoryDoc = await historyAppDocRef.get();
        if (existingHistoryDoc.exists) {
            functions.logger.warn(`Application ${currentApplicationIdToArchive} already exists in user history. Resetting current progress only, no re-archiving.`);
            // If already in history, just reset the current state to allow a new application.
            // This handles cases where the function might be called multiple times for an already archived application.

            // Generate a new ID for the next application cycle
            // Use a specific collection path for generating new IDs to avoid conflicts/side effects
            const tempNewAppId = db.collection('application_id_generators').doc().id;

            await userAdoptDocRef.update({
                currentApplicationId: tempNewAppId,
                lastArchivedApplicationId: currentApplicationIdToArchive,
                lastArchivedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            return {
                success: true,
                message: 'Application was already archived. Current progress has been reset for a new application.',
                newApplicationId: tempNewAppId
            };
        }

        // --- Get the main `adoption_progress` document for the specific application ID ---
        // Path: adoption_progress/{userId}/{applicationId}
        const activeAdoptionProgressDocRef = db.collection('adoption_progress')
            .doc(userId)
            .collection('applications') // Assuming 'applications' subcollection under userId
            .doc(currentApplicationIdToArchive);

        const activeAdoptionProgressSnapshot = await activeAdoptionProgressDocRef.get();
        if (!activeAdoptionProgressSnapshot.exists) {
            throw new functions.https.HttpsError(
                'not-found',
                `No active 'adoption_progress' document found at ${activeAdoptionProgressDocRef.path}`
            );
        }

        // The main progress data (e.g., {step1: "complete", step2: "in_progress", ...})
        const adoptProgressMap = activeAdoptionProgressSnapshot.data()?.adopt_progress || {};

        // 4. Server-side validation: Check if all steps are actually complete in the `adopt_progress` map
        const allStepsComplete = Array.from({ length: 10 }, (_, i) => i + 1).every(stepNum =>
            adoptProgressMap[`step${stepNum}`] === 'complete'
        );

        if (!allStepsComplete) {
            throw new functions.https.HttpsError(
                'failed-precondition',
                'Not all adoption steps are marked as complete. Archiving prevented by server-side check.'
            );
        }

        // --- 5. Copying Data to History ---

        // Archive the main adopt_progress document data directly
        await historyAppDocRef.set({
            originalApplicationId: currentApplicationIdToArchive,
            archivedAt: admin.firestore.FieldValue.serverTimestamp(),
            status: 'completed',
            username: activeAdoptionProgressSnapshot.data()?.username || userAdoptSnapshot.data()?.username || 'Unknown User',
            adopt_progress_summary: activeAdoptionProgressSnapshot.data() // This now copies the entire document content
        });
        functions.logger.log(`Archived main 'adoption_progress' document data for ${currentApplicationIdToArchive}`);

        // Copy user_submissions_status data (stepX_documents)
        // Source: user_submissions_status/{userId}/applications/{applicationId}/stepX_documents/{docId}
        for (let i = 1; i <= 10; i++) {
            const sourceRef = db.collection('user_submissions_status')
                .doc(userId)
                .collection('applications') // Assuming 'applications' subcollection
                .doc(currentApplicationIdToArchive)
                .collection(`step${i}_documents`);
            const destRef = historyAppDocRef.collection('submission_status_data').collection(`step${i}_documents`);
            await copyCollection(sourceRef, destRef, batchSize);
            functions.logger.log(`Copied submission status for step ${i} to history.`);
        }

        // Copy adoption_progress uploads data (stepX_uploads)
        // Source: adoption_progress/{userId}/applications/{applicationId}/stepX_uploads/{docId}
        for (let i = 1; i <= 10; i++) {
            const sourceRef = db.collection('adoption_progress')
                .doc(userId)
                .collection('applications') // Assuming 'applications' subcollection
                .doc(currentApplicationIdToArchive)
                .collection(`step${i}_uploads`);
            const destRef = historyAppDocRef.collection('upload_details_data').collection(`step${i}_uploads`);
            await copyCollection(sourceRef, destRef, batchSize);
            functions.logger.log(`Copied upload details for step ${i} to history.`);
        }

        // Copy admin comments
        // Source: adoption_progress/{userId}/applications/{applicationId}/comments/step_comments/{docId}
        const sourceCommentsRef = db.collection('adoption_progress')
            .doc(userId)
            .collection('applications') // Assuming 'applications' subcollection
            .doc(currentApplicationIdToArchive)
            .collection('comments') // 'comments' as a subcollection
            .doc('step_comments') // 'step_comments' as a document under 'comments'
            .collection('comments_list'); // Assuming actual comments are in a subcollection under step_comments

        // Destination for comments in history
        const destCommentsRef = historyAppDocRef.collection('comments_data');
        await copyCollection(sourceCommentsRef, destCommentsRef, batchSize);
        functions.logger.log(`Copied admin comments to history.`);


        // --- 6. Deleting Original Data from Active Collections ---
        // This clears the original paths, making the app's current application view truly "fresh."

        // Delete user_submissions_status subcollections
        for (let i = 1; i <= 10; i++) {
            const submissionsToDeleteQuery = db.collection('user_submissions_status')
                .doc(userId)
                .collection('applications') // Assuming 'applications' subcollection
                .doc(currentApplicationIdToArchive)
                .collection(`step${i}_documents`);
            await deleteQueryBatch(submissionsToDeleteQuery, batchSize);
            functions.logger.log(`Deleted active submission documents for step ${i}.`);
        }
        // Delete the parent document for user_submissions_status (e.g., user_submissions_status/{userId}/applications/{applicationId})
        const userSubmissionsApplicationDocRef = db.collection('user_submissions_status')
            .doc(userId)
            .collection('applications') // Assuming 'applications' subcollection
            .doc(currentApplicationIdToArchive);
        const userSubmissionsApplicationDocSnapshot = await userSubmissionsApplicationDocRef.get();
        if (userSubmissionsApplicationDocSnapshot.exists) {
            await userSubmissionsApplicationDocRef.delete();
            functions.logger.log(`Deleted user_submissions_status application document for ${currentApplicationIdToArchive}.`);
        }


        // Delete adoption_progress subcollections (uploads and comments)
        for (let i = 1; i <= 10; i++) {
            const uploadsToDeleteQuery = db.collection('adoption_progress')
                .doc(userId)
                .collection('applications') // Assuming 'applications' subcollection
                .doc(currentApplicationIdToArchive)
                .collection(`step${i}_uploads`);
            await deleteQueryBatch(uploadsToDeleteQuery, batchSize);
            functions.logger.log(`Deleted active uploads for step ${i}.`);
        }

        // Delete comments subcollection (comments_list)
        const commentsSubcollectionRefToDelete = db.collection('adoption_progress')
            .doc(userId)
            .collection('applications') // Assuming 'applications' subcollection
            .doc(currentApplicationIdToArchive)
            .collection('comments')
            .doc('step_comments')
            .collection('comments_list'); // The actual comments subcollection
        await deleteQueryBatch(commentsSubcollectionRefToDelete, batchSize);
        functions.logger.log(`Deleted active comments_list subcollection.`);

        // Delete the 'step_comments' document
        const stepCommentsDocRef = db.collection('adoption_progress')
            .doc(userId)
            .collection('applications') // Assuming 'applications' subcollection
            .doc(currentApplicationIdToArchive)
            .collection('comments')
            .doc('step_comments');
        const stepCommentsDocSnapshot = await stepCommentsDocRef.get();
        if (stepCommentsDocSnapshot.exists) {
            await stepCommentsDocRef.delete();
            functions.logger.log(`Deleted active step_comments document.`);
        }

        // Delete the 'comments' parent document
        const commentsParentDocRef = db.collection('adoption_progress')
            .doc(userId)
            .collection('applications') // Assuming 'applications' subcollection
            .doc(currentApplicationIdToArchive)
            .doc('comments');
        const commentsParentDocSnapshot = await commentsParentDocRef.get();
        if (commentsParentDocSnapshot.exists) {
            await commentsParentDocRef.delete();
            functions.logger.log(`Deleted active comments parent document.`);
        }

        // Delete the main adoption_progress document for this application instance
        // Path: adoption_progress/{userId}/applications/{applicationId}
        const adoptionProgressApplicationDocToDeleteRef = db.collection('adoption_progress')
            .doc(userId)
            .collection('applications') // Assuming 'applications' subcollection
            .doc(currentApplicationIdToArchive);
        const adoptionProgressApplicationDocSnapshot = await adoptionProgressApplicationDocToDeleteRef.get();
        if (adoptionProgressApplicationDocSnapshot.exists) {
            await adoptionProgressApplicationDocToDeleteRef.delete();
            functions.logger.log(`Deleted active adoption_progress application document for ${currentApplicationIdToArchive}.`);
        }

        functions.logger.log(`Deleted all active application data for ${currentApplicationIdToArchive}.`);

        // --- 7. Update useradopt with a new currentApplicationId ---
        // Generates a new unique ID for the next application cycle
        // Use a distinct collection for generating new IDs to ensure uniqueness without side effects on other data.
        const newApplicationId = db.collection('application_id_generators').doc().id;
        await userAdoptDocRef.update({
            currentApplicationId: newApplicationId,
            lastArchivedApplicationId: currentApplicationIdToArchive,
            lastArchivedAt: admin.firestore.FieldValue.serverTimestamp()
        });
        functions.logger.log(`User ${userId}: new currentApplicationId set to ${newApplicationId}.`);

        // 8. Return success to the client with the new application ID.
        return {
            success: true,
            message: 'Application successfully archived and new application started.',
            newApplicationId: newApplicationId
        };

    } catch (error) {
        functions.logger.error(`Error archiving application ${currentApplicationIdToArchive} for user ${userId}:`, error);
        // It's good practice to check if the error is already an HttpsError
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError(
            'internal',
            `Failed to archive application. Details: ${error.message}`,
            error.message
        );
    }
});
/**
 * Cloud Function: updateFirestoreOnEmailVerification
 * Triggered when a user document in the 'users' collection is updated.
 * Specifically checks for a change in the 'emailVerifiedInFirestore' field
 * (which your client-side app should update upon Firebase Auth email verification).
 */
exports.updateFirestoreOnEmailVerification = functions.firestore
  .document('users/{userId}') // Listen to updates on user documents in the 'users' collection
  .onUpdate(async (change, context) => {
    const afterData = change.after.data();
    const beforeData = change.before.data();
    const userId = context.params.userId;
    const db = admin.firestore(); // Use db here for consistency

    // Check if the 'emailVerifiedInFirestore' field changed from false/undefined to true
    if (afterData.emailVerifiedInFirestore === true && beforeData.emailVerifiedInFirestore !== true) {
        // You might want to also ensure the user's email in Auth is verified,
        // though the client should ensure this before setting emailVerifiedInFirestore.
        const authUser = await admin.auth().getUser(userId);
        if (authUser.emailVerified) {
            console.log(`User ${userId} emailVerified status confirmed and updated in Firestore via client.`);
            // No need to update Firestore again as the client already did this.
            // You can add other logic here if needed (e.g., sending a welcome email,
            // updating another record, etc.)
        } else {
            console.warn(`User ${userId} emailVerifiedInFirestore was set to true, but Auth email is not verified.`);
        }
    } else {
        console.log(`User ${userId} document updated, but emailVerifiedInFirestore status did not change to true or was already true.`);
    }
    return null;
});