package com.player2.playerengine.player2api;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Detached checks for emergency stop parsing and stale-turn invalidation. */
public final class ConversationControlSelfTest {
    private ConversationControlSelfTest() {}

    public static void runAll() {
        strictStopPhrases();
        staleTurnsAreInvalidated();
        callbackDrivenLlmFollowUpUsesAuthorizedHandoff();
        cancelledLlmRequestCannotHoldOrReleaseReplacementBucket();
        retiredWorkerQuarantineIsBounded();
        queuedCancellationCannotStartDuringShutdown();
        userMessageMetadataSurvivesCleaning();
    }

    private static void strictStopPhrases() {
        require(new StopIntent.Named(null, "ellie").equals(StopIntent.parse("stop ellie")),
                "reported trailing-name stop form must match");
        require(new StopIntent.Named(null, "ellie").equals(StopIntent.parse("Ellie, stop")),
                "reported leading-name stop form must match");
        require(new StopIntent.Named(null, "ellie").equals(StopIntent.parse("@Ellie: stop")),
                "explicit addressed stop must match");
        require(StopIntent.parse("Ellie stop planting") == null, "extra action text must not match");
        require(StopIntent.parse("stop") == null, "unnamed stop must remain on the normal range gate");
    }

    private static void staleTurnsAreInvalidated() {
        ConversationTurnGate gate = new ConversationTurnGate();
        long beforeStop = gate.issueTicket();
        require(gate.accepts(beforeStop), "fresh model turn ticket must be current");
        gate.invalidate();
        require(!gate.accepts(beforeStop), "pre-stop model turn must become stale");
        long afterStop = gate.issueTicket();
        require(gate.accepts(afterStop), "post-stop model turn ticket must be current");
        require(!gate.accepts(beforeStop), "a new turn must not revive a pre-stop ticket");
        long replacement = gate.issueTicket();
        require(!gate.accepts(afterStop) && gate.accepts(replacement),
                "a replacement request must supersede every older callback ticket");
    }

    private static void cancelledLlmRequestCannotHoldOrReleaseReplacementBucket() {
        LLMCompleter completer = new LLMCompleter();
        try {
            LatchService oldService = new LatchService(true);
            CountDownLatch oldCallback = new CountDownLatch(1);
            LLMCompleter.Submission old = completer.processToJson(
                    oldService, new ConversationHistory("system"), ignored -> oldCallback.countDown(),
                    ignored -> oldCallback.countDown(), true, AiTaskClass.DECISION);
            require(old.accepted(), "first LLM request must be accepted");
            await(oldService.started, "first LLM worker must start");
            require(completer.cancel(old) == LLMCompleter.CancellationOutcome.CANCELLED_AND_RETIRED,
                    "matching owner-stop cancellation must retire the first request");
            require(completer.isAvailible(), "cancelled request must release the billing bucket immediately");

            LatchService replacementService = new LatchService(false);
            CountDownLatch replacementCallback = new CountDownLatch(1);
            LLMCompleter.Submission replacement = completer.processToJson(
                    replacementService, new ConversationHistory("system"), ignored -> replacementCallback.countDown(),
                    ignored -> replacementCallback.countDown(), true, AiTaskClass.DECISION);
            require(replacement.accepted(), "replacement request must run on a fresh worker");
            await(replacementService.started, "replacement LLM worker must start");

            oldService.release.countDown();
            await(oldCallback, "retired request callback must eventually drain");
            long retiredSettleDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (System.nanoTime() < retiredSettleDeadline) {
                require(!completer.isAvailible(),
                        "retired request completion must not release the active replacement request");
                Thread.yield();
            }

            replacementService.release.countDown();
            await(replacementCallback, "replacement callback must complete");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!completer.isAvailible() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            require(completer.isAvailible(), "replacement completion must release its own billing bucket");
        } finally {
            completer.shutdown();
        }
    }

    private static void callbackDrivenLlmFollowUpUsesAuthorizedHandoff() {
        LLMCompleter completer = new LLMCompleter();
        try {
            LatchService firstService = new LatchService(false);
            LatchService followUpService = new LatchService(false);
            AtomicReference<LLMCompleter.Submission> firstRef = new AtomicReference<>();
            AtomicReference<LLMCompleter.Submission> followUpRef = new AtomicReference<>();
            CountDownLatch firstCallback = new CountDownLatch(1);
            CountDownLatch followUpCallback = new CountDownLatch(1);

            LLMCompleter.Submission first = completer.processToJson(
                    firstService, new ConversationHistory("system"), ignored -> {
                        followUpRef.set(completer.processToJsonAfter(
                                firstRef.get(), followUpService, new ConversationHistory("system"),
                                ignoredFollowUp -> followUpCallback.countDown(),
                                ignoredError -> followUpCallback.countDown(), true, AiTaskClass.DECISION));
                        firstCallback.countDown();
                    }, ignored -> firstCallback.countDown(), true, AiTaskClass.DECISION);
            firstRef.set(first);
            await(firstService.started, "first handoff request must start");
            firstService.release.countDown();
            await(firstCallback, "first handoff callback must run");
            require(followUpRef.get() != null && followUpRef.get().accepted(),
                    "callback-driven follow-up must atomically replace its active predecessor");
            await(followUpService.started, "authorized follow-up worker must start after its predecessor callback");
            followUpService.release.countDown();
            await(followUpCallback, "authorized follow-up callback must complete");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!completer.isAvailible() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            require(completer.isAvailible(), "follow-up must release its own request id");
        } finally {
            completer.shutdown();
        }
    }

    private static void retiredWorkerQuarantineIsBounded() {
        LLMCompleter completer = new LLMCompleter();
        LatchService[] services = {
                new LatchService(true), new LatchService(true), new LatchService(true)
        };
        CountDownLatch[] callbacks = {
                new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        try {
            for (int i = 0; i < services.length; i++) {
                final int index = i;
                LLMCompleter.Submission submission = completer.processToJson(
                        services[i], new ConversationHistory("system"),
                        ignored -> callbacks[index].countDown(), ignored -> callbacks[index].countDown(),
                        true, AiTaskClass.DECISION);
                require(submission.accepted(), "quarantine test request " + i + " must be accepted");
                await(services[i].started, "quarantine test request " + i + " must start");
                LLMCompleter.CancellationOutcome outcome = completer.cancel(submission);
                if (i < 2) {
                    require(outcome == LLMCompleter.CancellationOutcome.CANCELLED_AND_RETIRED,
                            "first two stuck workers may be retired within the bound");
                } else {
                    require(outcome == LLMCompleter.CancellationOutcome.RETIREMENT_LIMIT_REACHED,
                            "third stuck worker must hit the bounded quarantine limit");
                    require(!completer.isAvailible(),
                            "bounded-limit request must keep the bucket busy until its transport drains");
                }
            }

            services[2].release.countDown();
            await(callbacks[2], "bounded-limit request must drain after transport release");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!completer.isAvailible() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            require(completer.isAvailible(), "drained bounded-limit request must release the bucket");

            for (int i = 0; i < 2; i++) {
                services[i].release.countDown();
                await(callbacks[i], "retired worker " + i + " must drain during cleanup");
            }
        } finally {
            for (LatchService service : services) {
                service.release.countDown();
            }
            completer.shutdown();
        }
    }

    private static void queuedCancellationCannotStartDuringShutdown() {
        LLMCompleter completer = new LLMCompleter();
        LatchService predecessorService = new LatchService(false);
        LatchService queuedService = new LatchService(false);
        CountDownLatch predecessorReturned = new CountDownLatch(1);
        CountDownLatch shutdownFlagSet = new CountDownLatch(1);
        CountDownLatch allowExecutorStop = new CountDownLatch(1);
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        Thread shutdownThread = null;
        try {
            LLMCompleter.Submission predecessor = completer.processToJson(
                    predecessorService, new ConversationHistory("system"), ignored -> predecessorReturned.countDown(),
                    ignored -> predecessorReturned.countDown(),
                    true, AiTaskClass.DECISION);
            await(predecessorService.started, "shutdown-race predecessor must start");
            LLMCompleter.Submission queued = completer.processToJsonAfter(
                    predecessor, queuedService, new ConversationHistory("system"), ignored -> { }, ignored -> { },
                    true, AiTaskClass.DECISION);
            require(queued.accepted(), "shutdown-race continuation must be queued");
            require(completer.cancel(queued) == LLMCompleter.CancellationOutcome.CANCELLED_QUEUED,
                    "queued continuation cancellation must not rotate a worker");

            shutdownThread = new Thread(() -> {
                try {
                    completer.shutdown(() -> {
                        shutdownFlagSet.countDown();
                        await(allowExecutorStop, "shutdown-race executor-stop barrier must open");
                    });
                } catch (Throwable failure) {
                    shutdownFailure.set(failure);
                }
            }, "llm-shutdown-race-self-test");
            shutdownThread.start();
            await(shutdownFlagSet, "shutdown-race flag must be visible before executor stop");
            predecessorService.release.countDown();
            await(predecessorReturned, "shutdown-race predecessor must return while executor remains live");
            try {
                require(!queuedService.started.await(500, TimeUnit.MILLISECONDS),
                        "cancelled queued continuation must never start during shutdown");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("shutdown-race assertion interrupted", interrupted);
            }
        } finally {
            predecessorService.release.countDown();
            queuedService.release.countDown();
            allowExecutorStop.countDown();
            if (shutdownThread != null) {
                try {
                    shutdownThread.join(TimeUnit.SECONDS.toMillis(3));
                    require(!shutdownThread.isAlive(), "shutdown-race shutdown thread must terminate");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("shutdown-race shutdown join interrupted", interrupted);
                }
            }
            completer.shutdown();
        }
        if (shutdownFailure.get() != null) {
            throw new AssertionError("shutdown-race shutdown failed", shutdownFailure.get());
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            require(latch.await(3, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static final class LatchService extends Player2APIService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean ignoreInterrupt;

        private LatchService(boolean ignoreInterrupt) {
            super(null, "self-test");
            this.ignoreInterrupt = ignoreInterrupt;
        }

        @Override
        public JsonObject completeConversation(ConversationHistory history, AiTaskClass taskClass) throws Exception {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    return new JsonObject();
                } catch (InterruptedException interrupted) {
                    if (!ignoreInterrupt) {
                        throw interrupted;
                    }
                    // Simulate a transport that ignores cancellation; the retired worker may finish,
                    // but its old request id must never own or release the replacement bucket.
                }
            }
        }
    }

    private static void userMessageMetadataSurvivesCleaning() {
        UUID user = UUID.randomUUID();
        Event.UserMessage original = new Event.UserMessage("Ellie, stop", "owner", true, user);
        Event.UserMessage cleaned = original.withMessage("stop");
        require(cleaned.fromVoice(), "cleaning must preserve voice provenance");
        require(user.equals(cleaned.authenticatedUserUuid()), "cleaning must preserve authenticated UUID");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
