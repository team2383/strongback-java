package org.strongback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Notifier;

public interface PeriodicExecutor {
    public void start();

    public void stop();

    public static PeriodicExecutor roboRIONotifier(long period, TimeUnit unit, Runnables runnables) {
        try {
            return new PeriodicExecutor() {
                private final double periodInSeconds = unit.toMillis(period) * 1000.0;
                private final Notifier notifier = new Notifier(runnables::run);

                @Override
                public void start() {
                    notifier.startPeriodic(periodInSeconds);
                }

                @Override
                public void stop() {
                    notifier.stop();
                }
            };
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new StrongbackRequirementException("Missing FPGA hardware or software", e);
        }
    }

    public static PeriodicExecutor executorService(long period, TimeUnit unit, Runnables runnables) {
        return new PeriodicExecutor() {
            private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

            @Override
            public void start() {
                executor.scheduleAtFixedRate(runnables::run, 0, period, unit);
            }

            @Override
            public void stop() {
                executor.shutdown();
            }
        };
    }

    public static PeriodicExecutor waitForDSPacket(Runnables runnables) {
        try {
            return new PeriodicExecutor() {
                private final ExecutorService executor = Executors.newSingleThreadExecutor();
                private final DriverStation ds = DriverStation.getInstance();

                @Override
                public void start() {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            runnables.run();
                            ds.waitForData();
                        }
                    });
                }

                @Override
                public void stop() {
                    executor.shutdownNow();
                }
            };
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new StrongbackRequirementException("Missing FPGA hardware or software", e);
        }
    }

    public static PeriodicExecutor roboRIONotifierWithFallback(long period, TimeUnit unit, Runnables runnables) {
        PeriodicExecutor executor;
        try {
            executor = PeriodicExecutor.roboRIONotifier(period, unit, runnables);
        } catch (StrongbackRequirementException e) {
            System.out.println("Failed to create native Notifier executor, falling back to Java implementation");
            executor = PeriodicExecutor.executorService(period, unit, runnables);
        }
        return executor;
    }

    public static PeriodicExecutor waitForDSPacketWithFallback(Runnables runnables) {
        PeriodicExecutor executor;
        try {
            executor = PeriodicExecutor.waitForDSPacket(runnables);
        } catch (StrongbackRequirementException e) {
            System.out.println("Failed to create native Notifier executor, falling back to Java implementation");
            executor = PeriodicExecutor.executorService(20, TimeUnit.MILLISECONDS, runnables);
        }
        return executor;
    }
}
