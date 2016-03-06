/*
 * Strongback
 * Copyright 2015, Strongback and individual contributors by the @authors tag.
 * See the COPYRIGHT.txt in the distribution for a full listing of individual
 * contributors.
 *
 * Licensed under the MIT License; you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://opensource.org/licenses/MIT
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.strongback;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import org.strongback.AsyncEventRecorder.EventWriter;
import org.strongback.Logger.Level;
import org.strongback.annotation.NotImplemented;
import org.strongback.annotation.ThreadSafe;
import org.strongback.command.Command;
import org.strongback.command.CommandState;
import org.strongback.command.Scheduler;
import org.strongback.command.Scheduler.CommandListener;
import org.strongback.components.Clock;
import org.strongback.components.Counter;
import org.strongback.components.Switch;
import org.strongback.control.Controller;

/**
 * Access point for a number of the higher-level Strongback functions. This class can be used within robot code or within unit
 * tests.
 *
 * <h2>Configuration</h2>
 * <p>
 * Strongback will by default use the system logger, FPGA time (if available), and an executor that operates on a 5 millisecond
 * execution period. If these defaults are not acceptable, then the Strongback library needs to be configured programmatically
 * before you use it.
 * <p>
 * To configure Strongback, do the following once in the initialization of your robot (perhaps very early in the
 * {@link edu.wpi.first.wpilibj.IterativeRobot#robotInit()} method):
 * <ol>
 * <li>call the {@link #configure()} method to obtain the {@link Configurator} instance,</li>
 * <li>call any combination of the "use" or "set" methods on the {@link Configurator} instance,</li>
 * <li>call the {@link Configurator#initialize() initialize()} method on the {@link Configurator} instance.</li>
 * </ol>
 * After that, the configuration should not be adjusted again, and any of the other Strongback methods can be used.
 * <p>
 * For example, the following code configures Strongback to use what happen to be the default logger, time system, 5ms executor
 * (that uses busy-wait loops rather than {@link Thread#sleep(long)}), and automatically recording data and events to files on
 * the RoboRIO:
 *
 * <pre>
 * Strongback.configure().useSystemLogger(Logger.Level.INFO).useFpgaTime().useExecutionPeriod(5, TimeUnit.MILLISECONDS)
 *         .useExecutionWaitMode(WaitMode.BUSY).initialize();
 * // Strongback is ready to use ...
 * </pre>
 *
 * @author Randall Hauch
 */
@ThreadSafe
public final class Strongback {

    public static final class Configurator {
        private Supplier<Function<String, Logger>> loggersSupplier = () -> str -> new SystemLogger().enable(Level.INFO);
        private Supplier<Clock> timeSystemSupplier = Clock::fpgaOrSystem;
        private volatile boolean initialized = false;
        private String dataRecorderFilenameRoot = "strongback";
        private String eventRecorderFilenameRoot = "strongback";

        private long executionPeriodInMilliseconds = 5;
        public long driverstationExecutorTimeoutInMilliseconds = 50;

        private int estimatedRecordDurationInSeconds = 180; // 3 minutes by
                                                            // default
        private long eventRecordFileSizeInBytes = 1024 * 1024 * 2; // 2 MB by
                                                                   // default
        private boolean recordCommandStateChanges = true;
        private Function<Iterable<DataRecorderChannel>, DataWriter> dataWriterFactory = this::createFileDataWriter;
        private Supplier<EventWriter> eventWriterFactory = this::createFileEventWriter;
        private final LongConsumer excessiveExecutorDelayHandler = null;
        private final Supplier<String> dataRecorderFilenameGenerator = new Supplier<String>() {
            private final Counter counter = Counter.unlimited(1);

            @Override
            public String get() {
                return dataRecorderFilenameRoot + "-data-" + counter.get() + ".dat";
            }
        };
        private final Supplier<String> eventRecorderFilenameGenerator = new Supplier<String>() {
            private final Counter counter = Counter.unlimited(1);

            @Override
            public String get() {
                return eventRecorderFilenameRoot + "-event-" + counter.get() + ".dat";
            }
        };

        protected DataWriter createFileDataWriter(Iterable<DataRecorderChannel> channels) {
            int writesPerSecond = (int) ((double) TimeUnit.SECONDS.toNanos(1) / TimeUnit.MILLISECONDS.toNanos(20));
            return new FileDataWriter(channels, dataRecorderFilenameGenerator, writesPerSecond,
                    estimatedRecordDurationInSeconds);
        }

        protected EventWriter createFileEventWriter() {
            return new FileEventWriter(eventRecorderFilenameGenerator, eventRecordFileSizeInBytes);
        }

        protected DataWriter createNetworkDataWriter(List<DataRecorderChannel> channels) {
            return null;
        }

        /**
         * Log messages to {@link SystemLogger System.out} at the specified level
         *
         * @param level the global logging level; may not be null
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator useSystemLogger(Logger.Level level) {
            if (level == null)
                throw new IllegalArgumentException("The system logging level may not be null");
            loggersSupplier = () -> (context) -> new SystemLogger().enable(level);
            return this;
        }

        /**
         * Log messages to custom {@link Logger} implementations based upon the supplied function that maps the string contexts
         * to custom loggers.
         *
         * @param loggers the custom function that produces a logger for a context; may not be null
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator useCustomLogger(Function<String, Logger> loggers) {
            if (loggers == null)
                throw new IllegalArgumentException("The custom loggers function may not be null");
            loggersSupplier = () -> loggers;
            return this;
        }

        /**
         * Determine the time using the RoboRIO's FPGA's hardware if available, or the system time if FPGA hardware is not
         * available.
         *
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator useFpgaTime() {
            timeSystemSupplier = Clock::fpgaOrSystem;
            return this;
        }

        /**
         * Determine the time using the JRE's {@link Clock#system() time system} .
         *
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator useSystemTime() {
            timeSystemSupplier = Clock::system;
            return this;
        }

        /**
         * Determine the time using a custom {@link Clock} implementation.
         *
         * @param clock the custom time system; may not be null
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator useCustomTime(Clock clock) {
            if (clock == null)
                throw new IllegalArgumentException("The custom time system may not be null");
            timeSystemSupplier = () -> clock;
            return this;
        }

        /**
         * Turn off the data recorder so that it does not record anything.
         *
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordNoData() {
            dataWriterFactory = null;
            return this;
        }

        /**
         * Record data to local files that begin with the given prefix.
         *
         * @param filenamePrefix the prefix for filenames; may not be null
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordDataToFile(String filenamePrefix) {
            if (filenamePrefix == null)
                throw new IllegalArgumentException("The filename prefix may not be null");
            dataRecorderFilenameRoot = filenamePrefix;
            dataWriterFactory = this::createFileDataWriter;
            return this;
        }

        /**
         * Record data to the network tables.
         *
         * @return this configurator so that methods can be chained together; never null
         */
        @NotImplemented
        public Configurator recordDataToNetworkTables() {
            throw new UnsupportedOperationException("Network data writer is not yet implemented");
            // dataWriterFactory = this::createNetworkDataWriter;
            // return this;
        }

        /**
         * Record data to a custom {@link DataWriter} by supplying the factory that will create the data writer.
         *
         * @param customWriterFactory the factory for the {@link DataWriter} instance; may not be null
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordDataTo(Function<Iterable<DataRecorderChannel>, DataWriter> customWriterFactory) {
            if (customWriterFactory == null)
                throw new IllegalArgumentException("The custom writer factory cannot be null");
            dataWriterFactory = customWriterFactory;
            return this;
        }

        /**
         * Set the estimated number of seconds that the data recorder will capture. This is used to estimate by the data
         * recorder to optimize any resources it uses.
         *
         * @param numberOfSeconds the estimated number of seconds of recorded data; must be non-negative
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordDuration(int numberOfSeconds) {
            if (numberOfSeconds < 0)
                throw new IllegalArgumentException("The number of seconds may not be negative");
            estimatedRecordDurationInSeconds = numberOfSeconds;
            return this;
        }

        /**
         * Record events to local files that begin with the given prefix.
         *
         * @param filenamePrefix the prefix for filenames; may not be null
         * @param sizeInBytes the size of the files in bytes; must be at least 1024 bytes
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordEventsToFile(String filenamePrefix, long sizeInBytes) {
            if (filenamePrefix == null)
                throw new IllegalArgumentException("The filename prefix may not be null");
            if (sizeInBytes < 1024)
                throw new IllegalArgumentException("The event file size must be at least 1024 bytes");
            eventRecorderFilenameRoot = filenamePrefix;
            eventRecordFileSizeInBytes = sizeInBytes;
            eventWriterFactory = this::createFileEventWriter;
            return this;
        }

        /**
         * Turn off the event recorder so that it does not record anything.
         *
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordNoEvents() {
            eventWriterFactory = null;
            return this;
        }

        /**
         * Automatically record all command state transitions to the event recorder.
         *
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordCommands() {
            recordCommandStateChanges = true;
            return this;
        }

        /**
         * Do not record any command state transitions to the event recorder.
         *
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator recordNoCommands() {
            recordCommandStateChanges = true;
            return this;
        }

        /**
         * Use the specified execution rate for Strongback's constant-period {@link Strongback#executor() executor}. The default
         * execution rate is 5 milliseconds.
         *
         * @param interval the interval for calling all registered {@link Executable}s; must be positive
         * @param unit the time unit for the interval; may not be null
         * @return this configurator so that methods can be chained together; never null
         */
        public Configurator useExecutionPeriod(long period, TimeUnit unit) {
            if (period <= 0)
                throw new IllegalArgumentException("The execution interval must be positive");
            if (unit == null)
                throw new IllegalArgumentException("The time unit may not be null");
            if (1 > unit.toMillis(period))
                throw new IllegalArgumentException("The interval must be at least 1 millisecond");
            executionPeriodInMilliseconds = unit.toMillis(period);
            return this;
        }

        /**
         * Complete the Strongback configuration and initialize Strongback so that it can be used.
         */
        public synchronized void initialize() {
            if (initialized) {
                loggersSupplier.get().apply("").warn(
                        "Strongback has already been initialized. Make sure you configure and initialize Strongback only once");
            }
            initialized = true;
            INSTANCE = new Strongback(this, Strongback.INSTANCE);
        }
    }

    private static final Configurator CONFIG = new Configurator();
    private static volatile Strongback INSTANCE = new Strongback(CONFIG, null);

    /**
     * Get the Strongback library configurator. Any configuration changes will take effect only after the
     * {@link Configurator#initialize()} method is called.
     *
     * @return the configuration; never null
     */
    public static Configurator configure() {
        return CONFIG;
    }

    /**
     * Start the Strongback functions, including the {@link #executor() Executor}, {@link #submit(Command) command scheduler},
     * and the {@link #dataRecorder() data recorder}. This does nothing if Strongback is already started.
     * <p>
     * This is often useful to call in {@code IterativeRobot.autonomousInit()} to start Strongback and prepare for any
     * autonomous based commands and start recording data and events.
     *
     * @see #restart()
     */
    public static void start() {
        INSTANCE.doStart();
    }

    /**
     * Ensure that Strongback is {@link #start() started} and, if it was already running, {@link #killAllCommands() kill all
     * currently-running commands}. It is equivalent to calling both {@code #start()} <em>and</em> {@code #killAllCommands()},
     * although it is a bit more efficient.
     * <p>
     * This is often useful to use in {@code IterativeRobot.teleopInit()} to ensure Strongback is running and to cancel any
     * commands that might still be running from autonomous mode.
     *
     * @see #start
     * @see #killAllCommands()
     */
    public static void restart() {
        INSTANCE.doRestart();
    }

    /**
     * Stop all currently-scheduled activity and flush all recorders. This is typically called by robot code when when the robot
     * becomes disabled. Should the robot re-enable, all aspects of Strongback will continue to work as before it was disabled.
     */
    public static void disable() {
        INSTANCE.killCommandsAndFlush();
    }

    public static void shutdown() {
        INSTANCE.doShutdown();
    }

    /**
     * Get Strongback's Executables
     *
     * ran during driverstation loop
     *
     * @return Strongback's executor; never null
     */
    public static Executables executables() {
        return INSTANCE.executables;
    }

    /**
     * Get Strongback's Executables that are used to store Controllers
     *
     * these are run in a {@link PeriodicExecutor} with a 5ms default interval.
     * <p>
     * all {@link Controller} and time sensitive {@link Executable} instances will be registered by default to this executor
     * <p>
     * However, care must be taken to prevent overloading the executor. Specifically, the executor must be able to perform all
     * work for all registered {@link Executable}s during the {@link Configurator#useControllerExecutionPeriod(long, TimeUnit)
     * configured execution interval}. If too much work is added, the executor may fall behind.
     *
     * @return Strongback's executor; never null
     */
    public static Executables fastExecutables() {
        return INSTANCE.strictExecutables;
    }

    /**
     * Get Strongback's global {@link Logger} implementation.
     *
     * @return Strongback's logger instance; never null
     * @see Configurator#useSystemLogger(org.strongback.Logger.Level)
     * @see Configurator#useCustomLogger(Function)
     */
    public static Logger logger() {
        return logger("");
    }

    /**
     * Get Strongback's global {@link Logger} implementation.
     *
     * @param context the context of the logger
     * @return Strongback's logger instance; never null
     * @see Configurator#useSystemLogger(org.strongback.Logger.Level)
     * @see Configurator#useCustomLogger(Function)
     */
    public static Logger logger(String context) {
        return INSTANCE.loggers.apply(context);
    }

    /**
     * Get Strongback's global {@link Logger} implementation.
     *
     * @param context the context of the logger
     * @return Strongback's logger instance; never null
     * @see Configurator#useSystemLogger(org.strongback.Logger.Level)
     * @see Configurator#useCustomLogger(Function)
     */
    public static Logger logger(Class<?> context) {
        return INSTANCE.loggers.apply(context.getName());
    }

    /**
     * Get Strongback's {@link Clock time system} implementation.
     *
     * @return Strongback's time system instance; never null
     */
    public static Clock timeSystem() {
        return INSTANCE.clock;
    }

    /**
     * Submit a {@link Command} to be executed by Strongback's internal scheduler.
     *
     * @param command the command to be submitted
     * @see Configurator#useExecutionPeriod(long, TimeUnit)
     * @see Configurator#useExecutionTimerMode(org.strongback.Strongback.Configurator.TimerMode)
     */
    public static void submit(Command command) {
        if (command != null) {
            INSTANCE.scheduler.submit(command);
        }
    }

    /**
     * Submit to Strongback's internal scheduler a {@link Command} that runs the supplied function one time and completes
     * immediately.
     *
     * @param executeFunction the function to be called during execution; may not be null
     */
    public static void submit(Runnable executeFunction) {
        submit(Command.create(executeFunction));
    }

    /**
     * Submit to Strongback's internal scheduler a {@link Command} that runs the supplied function one time, waits the
     * prescribed amount of time, and then calls the second function.
     *
     * @param first the first function to be called; may not be null
     * @param delayInSeconds the delay in seconds after the first function completes; must be positive
     * @param second the second function to be called after the delay; may be null if not needed
     */
    public static void submit(Runnable first, double delayInSeconds, Runnable second) {
        submit(Command.create(delayInSeconds, first, second));
    }

    /**
     * Submit to Strongback's internal scheduler a {@link Command} that runs the supplied function one or more times until it
     * returns <code>false</code> or until the prescribed maximum time has passed, whichever comes first.
     *
     * @param function the function to be called at least one time and that should return <code>true</code> if it is to be
     *        called again; may not be null
     * @param maxDurationInSeconds the maximum amount of time that the first function should be repeatedly called; must be
     *        positive
     */
    public static void submit(BooleanSupplier function, double maxDurationInSeconds) {
        submit(Command.create(maxDurationInSeconds, function));
    }

    /**
     * Submit to Strongback's internal scheduler a {@link Command} that runs the supplied function one or more times until it
     * returns <code>false</code> or until the prescribed maximum time has passed, and then calls the second function.
     *
     * @param first the first function to be called at least one time and that should return <code>true</code> if it is to be
     *        called again; may not be null
     * @param maxDurationInSeconds the maximum amount of time that the first function should be repeatedly called; must be
     *        positive
     * @param second the second function to be called after the delay; may be null if not needed
     */
    public static void submit(BooleanSupplier first, double maxDurationInSeconds, Runnable second) {
        submit(Command.create(maxDurationInSeconds, first, second));
    }

    /**
     * Kill all currently-running commands.
     */
    public static void killAllCommands() {
        INSTANCE.scheduler.killAll();
    }

    /**
     * Flush all data that has been recorded but not written to disk.
     */
    public static void flushRecorders() {
        INSTANCE.dataRecorderDriver.flush();
    }

    /**
     * Get Strongback's {@link SwitchReactor} that can be used to call functions when {@link Switch switches} change state or
     * while they remain in a specific state. The switch reactor is registered with the {@link #executor() executor}, so it
     * periodically polls the registered switches and, based upon the current and previous states invokes the appropriate
     * registered functions.
     * <p>
     * This is a great way to perform some custom logic based upon {@link Switch} states. For example, you could submit a
     * specific command every time a button is pressed, or submit a command when a button is released, or run a command while a
     * button is pressed. See {@link SwitchReactor} for details.
     *
     * @return the switch reactor; never null
     * @see SwitchReactor
     * @see Configurator#useExecutionPeriod(long, TimeUnit)
     */
    public static SwitchReactor switchReactor() {
        return INSTANCE.switchReactor;
    }

    /**
     * Get Strongback's {@link DataRecorder} that can be used to register switches, motors, and other functions that provide
     * recordable data. Once data providers have been registered, Strongback will only begin recording data after Strongback is
     * {@link #start() started}, at which time the data recorder will automatically and repeatedly poll the data providers and
     * write out the information to its log. Strongback should be {@link #disable() disabled} when the robot is disabled to
     * flush any unwritten data and prevent recording data while in disabled mode. When the robot is enabled, it should again be
     * started.
     *
     * @return the data recorder; never null
     * @see DataRecorder
     * @see Configurator#recordDataTo(Function)
     * @see Configurator#recordDataToFile(String)
     * @see Configurator#recordDataToNetworkTables()
     * @see Configurator#recordDuration(int)
     * @see Configurator#recordNoData()
     */
    public static DataRecorder dataRecorder() {
        return INSTANCE.dataRecorderChannels;
    }

    /**
     * Get Strongback's {@link EventRecorder} used to record non-regular events and activities. If Strongback is configured to
     * {@link Configurator#recordCommands() automatically record commands}, then all changes to command states are recorded in
     * this event log. However, custom code can also explicitly {@link EventRecorder#record(String, String) record events} to
     * the same log.
     *
     * @return the event recorder
     * @see EventRecorder
     * @see Configurator#recordCommands()
     * @see Configurator#recordNoCommands()
     * @see Configurator#recordEventsToFile(String, long)
     * @see Configurator#recordNoEvents()
     */
    public static EventRecorder eventRecorder() {
        return INSTANCE.eventRecorder;
    }

    private final Function<String, Logger> loggers;
    private final PeriodicExecutor strictExecutor;
    private final PeriodicExecutor dsPacketExecutor;
    private final Executables strictExecutables;
    private final Executables executables;
    private final Clock clock;
    private final Scheduler scheduler;
    private final AsyncSwitchReactor switchReactor;
    private final DataRecorderChannels dataRecorderChannels;
    private final DataRecorderDriver dataRecorderDriver;
    private final EventRecorder eventRecorder;
    private final AtomicLong executorDelayCounter = new AtomicLong();
    private final LongConsumer excessiveExecutionHandler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private Strongback(Configurator config, Strongback previousInstance) {
        boolean start = false;
        if (previousInstance != null) {
            start = previousInstance.started.get();
            // Terminates all currently-scheduled commands and stops the executor's thread (if running) ...
            previousInstance.doShutdown();
            strictExecutables = previousInstance.strictExecutables;
            executables = previousInstance.executables;
            switchReactor = previousInstance.switchReactor;

            executables.unregister(previousInstance.dataRecorderDriver);
            executables.unregister(previousInstance.eventRecorder);
            executables.unregister(previousInstance.scheduler);
            dataRecorderChannels = previousInstance.dataRecorderChannels;
            excessiveExecutionHandler = previousInstance.excessiveExecutionHandler;
        } else {
            executables = new Executables();
            strictExecutables = new Executables();
            switchReactor = new AsyncSwitchReactor();
            executables.register(switchReactor);
            dataRecorderChannels = new DataRecorderChannels();
            excessiveExecutionHandler = config.excessiveExecutorDelayHandler;
        }
        loggers = config.loggersSupplier.get();
        clock = config.timeSystemSupplier.get();
        // Create a new executor ...
        strictExecutor = PeriodicExecutor
                .roboRIONotifierWithFallback("strictExecutor",
                        config.executionPeriodInMilliseconds, TimeUnit.MILLISECONDS,
                        strictExecutables, clock, loggers.apply("strictExecutor"),
                        monitorDelay("strictExecutor", 100, TimeUnit.MILLISECONDS));

        dsPacketExecutor = PeriodicExecutor.waitForDSPacketWithFallback("dsPacketExecutor",
                config.driverstationExecutorTimeoutInMilliseconds, TimeUnit.MILLISECONDS, executables, clock,
                loggers.apply("dsPacketExecutor"),
                monitorDelay("dsPacketExecutor", 100, TimeUnit.MILLISECONDS));

        // Create a new event recorder ...
        if (config.eventWriterFactory != null) {
            eventRecorder = new AsyncEventRecorder(config.eventWriterFactory.get(), clock);
            executables.register(eventRecorder);
        } else

        {
            eventRecorder = EventRecorder.noOp();
        }

        // Create a new scheduler that optionally records command state
        // transitions. Note that we ignore everything in
        // the previous instance's scheduler, since all commands would have been
        // terminated (as intended) ...
        CommandListener commandListener = config.recordCommandStateChanges ? this::recordCommand
                : this::recordNoCommands;
        scheduler = new Scheduler(loggers.apply("scheduler"), commandListener);
        executables.register(scheduler);

        // Create a new data recorder driver ...
        dataRecorderDriver = new DataRecorderDriver(dataRecorderChannels, config.dataWriterFactory);
        executables.register(dataRecorderDriver);

        // Start this if the previous was already started ...
        if (previousInstance != null && start)

        {
            doStart();
        }

    }

    private LongConsumer monitorDelay(String executorName, long executionInterval, TimeUnit unit) {
        long intervalInMs = unit.toMillis(executionInterval);
        return delayInMs -> {
            if (delayInMs > intervalInMs) {
                executorDelayCounter.incrementAndGet();
                if (excessiveExecutionHandler != null) {
                    try {
                        excessiveExecutionHandler.accept(delayInMs);
                    } catch (Throwable t) {
                        logger().error(t,
                                "Error with custom handler for excessive execution times for executor " + executorName + ".");
                    }
                } else {
                    logger().error("Delay is : " + delayInMs + ". Unable to execute all activities within " + intervalInMs
                            + " milliseconds for "
                            + executorName + "!");
                }
            }
        };
    }

    private void recordCommand(Command command, CommandState state) {
        eventRecorder.record(command.getClass().getName(), state.ordinal());
    }

    private void recordNoCommands(Command command, CommandState state) {
    }

    private void doStart() {
        if (!started.get()) {
            try {
                dataRecorderDriver.start();
            } finally {
                try {
                    strictExecutor.start();
                } finally {
                    try {
                        dsPacketExecutor.start();
                    } finally {
                        started.set(true);
                    }
                }
            }
        }
    }

    private void doRestart() {
        if (started.get()) {
            // Kill any remaining commands ...
            scheduler.killAll();
        } else {
            try {
                dataRecorderDriver.start();
            } finally {
                try {
                    strictExecutor.start();
                } finally {
                    try {
                        dsPacketExecutor.start();
                    } finally {
                        started.set(true);
                    }
                }
            }
        }
    }

    private void killCommandsAndFlush() {
        if (started.get()) {
            try {
                // Kill any remaining commands ...
                scheduler.killAll();
            } finally {
                // Finally flush the data recorder ...
                dataRecorderDriver.flush();
            }
        }
    }

    private void doShutdown() {
        try {
            // First stop executing immediately; at this point, no Executables
            // will run ...
            strictExecutor.stop();
        } finally {
            try {
                dsPacketExecutor.stop();
            } finally {
                try {
                    // Kill any remaining commands ...
                    scheduler.killAll();
                } finally {
                    try {
                        // Finally flush the data recorder ...
                        dataRecorderDriver.stop();
                    } finally {
                        started.set(false);
                    }
                }
            }

        }
    }
}
