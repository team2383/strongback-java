package org.strongback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import org.strongback.components.Clock;
import org.strongback.components.Stoppable;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Notifier;

public abstract class PeriodicExecutor implements Stoppable {
    protected final String name;
    protected final Logger logger;
    protected final Clock clock;
    private final Iterable<Executable> executables;
    private final LongConsumer delayInformer;
    private long timeInMillis = 0L;
    private long lastTimeInMillis = 0L;

    public PeriodicExecutor(String name, Iterable<Executable> executables,
                            Clock clock, Logger logger, LongConsumer delayInformer) {
        this.name = name;
        this.clock = clock;
        this.logger = logger;
        this.executables = executables;
        this.delayInformer = delayInformer != null ? delayInformer : (x) -> {
        };
    }

    public abstract void start();

    @Override
    public abstract void stop();

    /**
     * Run one tick worth of executables eg. Run each executable once.
     */
    protected void run() {
        timeInMillis = clock.currentTimeInMillis();
        delayInformer.accept(lastTimeInMillis != 0 ? timeInMillis - lastTimeInMillis : 0);
        try {
            executables.forEach((e) -> e.execute(timeInMillis));
        } catch (Throwable e) {
            logger.error(e, "Error in notifier :" + name);
        }
        lastTimeInMillis = timeInMillis;
    }

    public static PeriodicExecutor roboRIONotifier(String name, long period, TimeUnit unit, Iterable<Executable> iterator,
            Clock clock, Logger logger, LongConsumer delayInformer) {
        try {
            return new PeriodicExecutor(name, iterator, clock, logger, delayInformer) {
                private final Notifier notifier = new Notifier(this::run);
                private final double periodInSeconds = unit.toMillis(period) / 1000.0;

                @Override
                public void start() {
                    logger.debug("roboRIONotifier " + name + " starting now...");
                    notifier.startPeriodic(periodInSeconds);
                }

                @Override
                public void stop() {
                    logger.debug("Notifier " + name + " stopping now...");
                    notifier.stop();
                }
            };
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new StrongbackRequirementException("Missing FPGA hardware or software", e);
        }
    }

    public static PeriodicExecutor executorService(String name, long period, TimeUnit unit, Iterable<Executable> executables,
            Clock clock, Logger logger, LongConsumer delayInformer) {
        return new PeriodicExecutor(name, executables, clock, logger, delayInformer) {
            private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

            @Override
            public void start() {
                logger.debug("roboRIONotifier " + name + " starting now...");
                executor.scheduleAtFixedRate(this::run, 0, period, unit);
            }

            @Override
            public void stop() {
                logger.debug("roboRIONotifier " + name + " stopping now...");
                executor.shutdownNow();
            }
        };
    }

    public static PeriodicExecutor waitForDSPacket(String name, long timeout, Iterable<Executable> executables,
            Clock clock, Logger logger, LongConsumer delayInformer) {
        try {
            return new PeriodicExecutor(name, executables, clock, logger, delayInformer) {
                private final ExecutorService executor = Executors.newSingleThreadExecutor();
                private final DriverStation ds = DriverStation.getInstance();

                @Override
                public void start() {
                    logger.debug("waitForDSPacket executor " + name + " starting now...");
                    executor.execute(this::run);
                }

                @Override
                public void stop() {
                    logger.debug("waitForDSPacket executor " + name + " stopping now...");
                    executor.shutdownNow();
                }

                @Override
                public void run() {
                    while (!Thread.interrupted()) {
                        super.run();
                        ds.waitForData(timeout);
                    }
                }
            };
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new StrongbackRequirementException("Missing FPGA hardware or software", e);
        }
    }

    public static PeriodicExecutor waitForDSPacketWithFallback(String name, long timeout, TimeUnit unit,
            Iterable<Executable> executables,
            Clock clock,
            Logger logger, LongConsumer delayInformer) {
        PeriodicExecutor executor;
        try {
            executor = PeriodicExecutor.waitForDSPacket(name, timeout, executables, clock, logger, delayInformer);
        } catch (StrongbackRequirementException e) {
            logger.error("Falling back to Java executor implementation");
            executor = PeriodicExecutor.executorService(name, 20, TimeUnit.MILLISECONDS, executables, clock, logger,
                    delayInformer);
        }
        return executor;
    }

    public static PeriodicExecutor roboRIONotifierWithFallback(String name, long period, TimeUnit unit,
            Iterable<Executable> iterator, Clock clock, Logger logger, LongConsumer delayInformer) {
        PeriodicExecutor executor;
        try {
            executor = PeriodicExecutor.roboRIONotifier(name, period, unit, iterator, clock, logger, delayInformer);
        } catch (StrongbackRequirementException e) {
            logger.error("Falling back to Java executor implementation");
            executor = PeriodicExecutor.executorService(name, period, unit, iterator, clock, logger, delayInformer);
        }
        return executor;
    }
}