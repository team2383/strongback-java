package org.strongback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.strongback.components.Clock;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Notifier;

public interface PeriodicExecutor {

    public void start();

    public void stop();

    public static PeriodicExecutor roboRIONotifier(long period, TimeUnit unit, Clock clock, Executables executables) {
        try {
            return new PeriodicExecutor() {
                private final double periodInSeconds = unit.toMillis(period) * 1000.0;
                private final Notifier notifier = new Notifier(executables.toRunnable(clock));

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

    public static PeriodicExecutor executorService(long period, TimeUnit unit, Clock clock, Executables executables) {
        return new PeriodicExecutor() {
            private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

            @Override
            public void start() {
                executor.scheduleAtFixedRate(executables.toRunnable(clock), 0, period, unit);
            }

            @Override
            public void stop() {
                executor.shutdown();
            }
        };
    }

    public static PeriodicExecutor waitForDSPacket(Clock clock, Executables executables) {
        try {
            return new PeriodicExecutor() {
                private final ExecutorService executor = Executors.newSingleThreadExecutor();
                private final DriverStation ds = DriverStation.getInstance();

                @Override
                public void start() {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            executables.execute(clock.currentTimeInMillis());
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

    public static PeriodicExecutor roboRIONotifierWithFallback(long period, TimeUnit unit, Clock clock,
            Executables executables) {
        PeriodicExecutor executor;
        try {
            executor = PeriodicExecutor.roboRIONotifier(period, unit, clock, executables);
        } catch (StrongbackRequirementException e) {
            System.out.println("Failed to create native Notifier executor, falling back to Java implementation");
            executor = PeriodicExecutor.executorService(period, unit, clock, executables);
        }
        return executor;
    }

    public static PeriodicExecutor waitForDSPacketWithFallback(Clock clock, Executables executables) {
        PeriodicExecutor executor;
        try {
            executor = PeriodicExecutor.waitForDSPacket(clock, executables);
        } catch (StrongbackRequirementException e) {
            System.out.println("Failed to create native Notifier executor, falling back to Java implementation");
            executor = PeriodicExecutor.executorService(20, TimeUnit.MILLISECONDS, clock, executables);
        }
        return executor;
    }
}
