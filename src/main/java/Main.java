import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.FileOutputStream;

/**
 * Entry point for a POSIX-like shell implemented in Java.
 *
 * Supports the following built-in commands:
 *   echo  - print arguments to stdout
 *   type  - identify whether a name is a built-in or an external executable
 *   exit  - terminate the shell
 *   pwd   - print the current working directory
 *   cd    - change the current working directory
 *
 * External executables found on PATH are delegated to ProcessBuilder.
 * I/O redirection (> / 1> / 2>) is parsed before dispatch.
 */
public class Main {

    /**
     * Holds the result of parsing a single input line.
     *
     * After splitting the raw input into tokens and stripping redirection
     * operators, this object carries:
     *   parts      - the command name followed by its arguments
     *   stdOutFile - destination file for stdout (null = inherit)
     *   stdErrFile - destination file for stderr (null = inherit)
     *   stdInFile  - source file for stdin   (null = inherit, currently unused)
     */
    static class ParsedCommand {

        String[] parts;
        File stdOutFile;
        File stdErrFile;
        File stdInFile;
        boolean appendStdOut = false;
        boolean appendStdErr = false;
        boolean backgroundExecution = false;

        ParsedCommand(String[] parts, File stdOutFile, File stdErrFile, File stdInFile) {
            this.parts = parts;
            this.stdOutFile = stdOutFile;
            this.stdErrFile = stdErrFile;
            this.stdInFile = stdInFile;
        }
    }




    static class Job{
        int id;
        Process process;
        String command;
        String status;
        static ArrayList<Job> jobs = new ArrayList<>();

        Job(int id, Process process, String command) {
            this.id = id;
            this.process = process;
            this.command = command;
            this.status = "Running";
        }

        static int nextAvailableId() {
            int id = 1;
            boolean idInUse = true;

            while (idInUse) {
                idInUse = false;
                for (Job job : jobs) {
                    if (job.id == id) {
                        idInUse = true;
                        id++;
                        break;
                    }
                }
            }

            return id;
        }
    }

    /** The shell's working directory, updated by `cd`. Starts at the JVM launch directory. */
    static Path currentDirectory = Path.of(System.getProperty("user.dir"));

    /** Names of all built-in commands. Used by `type` to distinguish builtins from PATH lookups. */
    static Set<String> commands = new HashSet<>(
        Arrays.asList("echo", "type", "exit", "pwd", "cd", "jobs")
    );

    /**
     * A shell built-in command.
     *
     * Each built-in receives explicit I/O streams rather than reaching for
     * System.in/out/err directly. This is what makes redirection and
     * pipelines work: the caller decides where the command's input comes
     * from and where its output goes, and the command stays oblivious.
     *
     *   in   - source for the command's stdin  (System.in, a file, or a pipe)
     *   out  - destination for stdout           (System.out, a file, or a pipe)
     *   err  - destination for stderr           (System.err or a file)
     */
    interface BuiltinCommand {
        void execute(ParsedCommand cmd, InputStream in, PrintStream out, PrintStream err) throws Exception;
    }

    /**
     * Registry mapping a built-in's name to its implementation.
     *
     * Dispatch in main() is a single map lookup against this registry — there
     * is no if/else chain. Adding a new built-in means writing a class that
     * implements BuiltinCommand and adding one line here; main() never changes.
     *
     * `exit` is intentionally absent: it controls the REPL lifecycle (closes
     * the Scanner and terminates the JVM) and is handled directly in main().
     */
    static Map<String, BuiltinCommand> builtins = new HashMap<>();
    static {
        builtins.put("echo", new EchoCommand());
        builtins.put("pwd", new PwdCommand());
        builtins.put("type", new TypeCommand());
        builtins.put("cd", new CdCommand());
        builtins.put("jobs", new JobsCommand());
    }

    /**
     * Implements the `echo` built-in.
     *
     * Prints all arguments (parts[1..]) space-separated with a trailing newline.
     * If no arguments are given, prints a blank line.
     */
    static class EchoCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            String[] parts = command.parts;
            if (parts.length == 1) {
                // No arguments — emit an empty line to match POSIX echo behaviour
                out.println("");
            } else {
                for (int i = 1; i < parts.length; i++) {
                    if (i == parts.length - 1) {
                        // Last argument: no trailing space
                        out.print(parts[i]);
                        break;
                    }
                    out.print(parts[i] + " ");
                }
                out.println();
            }
        }
    }

    /**
     * Implements the `type` built-in.
     *
     * Reports whether the named command is:
     *   - a shell built-in  ("foo is a shell builtin")
     *   - an executable on PATH  ("foo is /usr/bin/foo")
     *   - not found  ("foo: not found")
     *
     * Writes to the supplied `out` stream so the result honours redirection
     * and pipelines (e.g. `type echo > file`).
     */
    static class TypeCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            String[] parts = command.parts;

            if (parts.length == 1) {
                out.println("No command mentioned");
                return;
            }

            String target = parts[1];

            if (commands.contains(target)) {
                out.println(target + " is a shell builtin");
                return;
            }

            // Fall through to PATH search
            Path executablePath = findExecutableInPath(target);
            if (executablePath != null) {
                out.println(target + " is " + executablePath);
            } else {
                out.println(target + ": not found");
            }
        }
    }

    /**
     * Searches the PATH environment variable for an executable with the given name.
     *
     * Iterates each directory listed in PATH (colon-separated on UNIX,
     * semicolon-separated on Windows) and returns the first Path that is
     * both a regular file and executable.
     *
     * @param command  the bare executable name (e.g. "ls", "grep")
     * @return the full Path to the executable, or null if not found
     */
    static Path findExecutableInPath(String command) {
        String PATH = System.getenv("PATH");
        // File.pathSeparator is ":" on UNIX, ";" on Windows
        String[] directories = PATH.split(File.pathSeparator);

        for (String directory : directories) {
            Path filePath = Path.of(directory, command);
            if (Files.isRegularFile(filePath) && Files.isExecutable(filePath)) {
                return filePath;
            }
        }
        return null;
    }

    /**
     * Implements the `pwd` built-in.
     *
     * Prints the shell's current logical working directory (currentDirectory),
     * which tracks `cd` calls rather than querying the OS each time.
     * Writes to `out` so the result honours redirection and pipelines.
     */
    static class PwdCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            out.println(currentDirectory);
        }
    }

    /**
     * Implements the `cd` built-in.
     *
     * Supported argument forms:
     *   (none) or "~"   - navigate to $HOME
     *   "~/foo"         - navigate to $HOME/foo
     *   "/abs/path"     - absolute path
     *   "rel/path"      - path relative to currentDirectory
     *
     * The resolved path is normalized (symlinks/".." collapsed) before
     * being assigned.  Prints an error if the target is not a directory.
     */
    static class CdCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            String[] parts = command.parts;
            Path target;
            String home = System.getenv("HOME");
            Path homePath = Path.of(home);

            if (parts.length <= 1 || (parts[1].startsWith("~") && parts[1].length() == 1)) {
                // "cd" or "cd ~" → go home
                currentDirectory = homePath;
                return;
            } else if (parts[1].startsWith("~/")) {
                // Strip the leading "~/" and resolve relative to $HOME
                String rem = parts[1].substring(2);
                target = homePath.resolve(rem);
            } else {
                Path destination = Path.of(parts[1]);

                if (destination.isAbsolute()) {
                    target = destination;
                } else {
                    // Relative path: resolve against the current shell directory
                    target = currentDirectory.resolve(destination);
                }
            }

            // Collapse any ".." or "." segments
            target = target.normalize();

            if (Files.isDirectory(target)) {
                currentDirectory = target;
            } else {
                // Write to the redirected stderr stream, not System.out
                err.println("cd: " + parts[1] + ": No such file or directory");
            }
        }
    }

    /**
     * Forks an external process for commands that are not built-ins.
     *
     * Uses ProcessBuilder so the child process inherits the shell's current
     * working directory.  I/O is configured based on the redirection files:
     *   - If stdOutFile is non-null, stdout is redirected to that file;
     *     stderr and stdin continue to be inherited from the terminal.
     *   - If stdOutFile is null, all three streams are inherited (inheritIO).
     *
     * Blocks until the child process exits.
     * @param command     the parsed command object containing parts and redirection info
     * @variable parts      command name + arguments array
     * @variable stdOutFile file to redirect stdout to, or null
     * @variable stdErrFile file to redirect stderr to, or null (currently not wired separately)
     * @variable stdInFile  file to read stdin from, or null (currently unused)
     */
    static void executeCommand(ParsedCommand command) throws Exception {
        String[] parts = command.parts;
        File stdOutFile = command.stdOutFile;
        File stdErrFile = command.stdErrFile;
        File stdInFile = command.stdInFile;
        boolean backgroundExecution = command.backgroundExecution;

        ProcessBuilder builder = new ProcessBuilder(parts);
        builder.directory(currentDirectory.toFile());

        // Always inherit all three streams first so unredirected ones stay on the terminal
        builder.inheritIO();
        if (stdOutFile != null) {
            builder.redirectOutput(command.appendStdOut 
                ? ProcessBuilder.Redirect.appendTo(stdOutFile)
                : ProcessBuilder.Redirect.to(stdOutFile));
        }
        if (stdErrFile != null) {
            builder.redirectError(command.appendStdErr 
                ? ProcessBuilder.Redirect.appendTo(stdErrFile)
                : ProcessBuilder.Redirect.to(stdErrFile));
        }
        if (command.backgroundExecution && stdInFile == null) {
            builder.redirectInput(new File("/dev/null"));
        }
        

        try {
            Process process = builder.start();
            if (backgroundExecution) {
                Job job = new Job(Job.nextAvailableId(), process, String.join(" ", parts));
                Job.jobs.add(job);
                System.err.println("[" + job.id + "] " + process.pid());
            } else {
                process.waitFor();
            }
        } catch (java.io.IOException e) {
            // Covers missing redirect-target parent dir, permission errors, etc.
            System.err.println(parts[0] + ": " + e.getMessage());
        }
    }

    /**
     * Splits a token array into pipeline stages at each "|" token.
     *
     * "ls -l | grep x | wc" → [ [ls,-l], [grep,x], [wc] ].
     * The "|" tokens themselves are dropped. Empty stages (e.g. a trailing
     * "|" with nothing after it) are preserved as zero-length arrays so the
     * caller can report a syntax error rather than silently ignoring them.
     *
     * @param parts tokenized input produced by parseInput()
     * @return one token array per stage; always at least one element
     */
    static List<String[]> splitPipeline(String[] parts) {
        List<String[]> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String p : parts) {
            if (p.equals("|")) {
                segments.add(current.toArray(new String[0]));
                current = new ArrayList<>();
            } else {
                current.add(p);
            }
        }
        segments.add(current.toArray(new String[0]));
        return segments;
    }

    /**
     * Runs a multi-stage pipeline concurrently, supporting both built-ins and
     * external commands in any position (e.g. `echo hi | cat`, `ls | grep x`,
     * `cat f | head -1`).
     *
     * Each stage runs on its own thread, connected to its neighbours by a
     * pair of PipedOutputStream/PipedInputStream "connectors". Stages must run
     * at the same time: a stage can fill its pipe buffer and block until the
     * next stage drains it, so a sequential stage-by-stage approach would
     * deadlock.
     *
     * Stream wiring per stage index i (of n):
     *   - stdin : i==0 → terminal (or `< file`); otherwise the previous connector
     *   - stdout: i==n-1 → terminal (or `> file`); otherwise the next connector
     *   - stderr: every stage honours its own `2> file`, else the terminal
     *
     * Built-ins are called directly with the connector streams. External
     * commands are launched with ProcessBuilder; when an endpoint is a
     * connector (a Java stream) rather than the terminal or a file, a pump
     * thread copies bytes between the connector and the process's pipe.
     *
     * Blocks until every stage has finished.
     *
     * @param stages one ParsedCommand per pipeline segment, in order
     */
    static void executePipeline(List<ParsedCommand> stages) throws Exception {
        final int n = stages.size();

        // Reject empty stages: "ls |", "| wc", or "ls || wc"
        for (ParsedCommand stage : stages) {
            if (stage.parts.length == 0) {
                System.out.println("Syntax error near unexpected token `|'");
                return;
            }
        }

        if (allStagesAreExternalCommands(stages)) {
            executeExternalPipeline(stages);
            return;
        }

        // One connector between each adjacent pair of stages. A generous buffer
        // reduces the chance of a writer blocking before its reader is scheduled.
        final PipedInputStream[] readEnds = new PipedInputStream[n - 1];
        final PipedOutputStream[] writeEnds = new PipedOutputStream[n - 1];
        for (int i = 0; i < n - 1; i++) {
            readEnds[i] = new PipedInputStream(64 * 1024);
            writeEnds[i] = new PipedOutputStream(readEnds[i]);
        }

        List<Thread> threads = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ParsedCommand stage = stages.get(i);
            final InputStream pipeIn  = (idx == 0)     ? null : readEnds[idx - 1];
            final OutputStream pipeOut = (idx == n - 1) ? null : writeEnds[idx];

            Thread t = new Thread(() -> {
                try {
                    runStage(stage, idx, n, pipeIn, pipeOut);
                } catch (Exception e) {
                    System.err.println(stage.parts[0] + ": " + e.getMessage());
                } finally {
                    // Closing our connector's write end signals EOF to the next
                    // stage's reader — without this, downstream blocks forever.
                    if (pipeOut != null) {
                        try { pipeOut.close(); } catch (IOException ignore) {}
                    }
                }
            });
            threads.add(t);
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
    }

    static boolean allStagesAreExternalCommands(List<ParsedCommand> stages) {
        for (ParsedCommand stage : stages) {
            String name = stage.parts[0];
            if (builtins.containsKey(name) || findExecutableInPath(name) == null) {
                return false;
            }
        }
        return true;
    }

    static void executeExternalPipeline(List<ParsedCommand> stages) throws Exception {
        List<ProcessBuilder> builders = new ArrayList<>();

        for (int i = 0; i < stages.size(); i++) {
            ParsedCommand stage = stages.get(i);
            ProcessBuilder builder = new ProcessBuilder(stage.parts);
            builder.directory(currentDirectory.toFile());

            if (i == 0) {
                builder.redirectInput(stage.stdInFile != null
                    ? ProcessBuilder.Redirect.from(stage.stdInFile)
                    : ProcessBuilder.Redirect.INHERIT);
            }

            if (i == stages.size() - 1) {
                if (stage.stdOutFile != null) {
                    builder.redirectOutput(stage.appendStdOut
                        ? ProcessBuilder.Redirect.appendTo(stage.stdOutFile)
                        : ProcessBuilder.Redirect.to(stage.stdOutFile));
                } else {
                    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
            }

            if (stage.stdErrFile != null) {
                builder.redirectError(stage.appendStdErr
                    ? ProcessBuilder.Redirect.appendTo(stage.stdErrFile)
                    : ProcessBuilder.Redirect.to(stage.stdErrFile));
            } else {
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            builders.add(builder);
        }

        List<Process> processes = ProcessBuilder.startPipeline(builders);
        for (Process process : processes) {
            process.waitFor();
        }
    }

    /**
     * Executes a single pipeline stage on the current thread.
     *
     * @param stage   the parsed command for this stage
     * @param idx     this stage's position (0-based)
     * @param n       total number of stages
     * @param pipeIn  the connector to read from, or null if this is the first stage
     * @param pipeOut the connector to write to, or null if this is the last stage
     */
    static void runStage(ParsedCommand stage, int idx, int n,
                         InputStream pipeIn, OutputStream pipeOut) throws Exception {
        BuiltinCommand builtin = builtins.get(stage.parts[0]);

        // ---- built-in stage: call directly with the connector streams ----
        if (builtin != null) {
            InputStream in = (idx == 0) ? System.in : pipeIn;

            PrintStream err = System.err;
            boolean closeErr = false;
            if (stage.stdErrFile != null) {
                err = new PrintStream(new FileOutputStream(stage.stdErrFile, stage.appendStdErr));
                closeErr = true;
            }

            PrintStream out;
            boolean closeOut = false;
            if (idx == n - 1) {
                if (stage.stdOutFile != null) {
                    out = new PrintStream(new FileOutputStream(stage.stdOutFile, stage.appendStdOut));
                    closeOut = true;
                } else {
                    out = System.out;
                }
            } else {
                // Wrap the connector; the worker thread closes the underlying
                // write end afterwards, so only flush here (never close stdout).
                out = new PrintStream(pipeOut);
            }

            try {
                builtin.execute(stage, in, out, err);
            } finally {
                out.flush();
                if (closeOut) out.close();
                if (closeErr) err.close();
            }
            return;
        }

        // ---- external stage ----
        if (findExecutableInPath(stage.parts[0]) == null) {
            System.err.println(stage.parts[0] + ": command not found");
            // Drain upstream so the previous stage doesn't block writing to us.
            if (pipeIn != null) pump(pipeIn, OutputStream.nullOutputStream(), false);
            return;
        }

        ProcessBuilder b = new ProcessBuilder(stage.parts);
        b.directory(currentDirectory.toFile());

        // stdin: first stage from terminal/file; otherwise pump from connector.
        if (idx == 0) {
            b.redirectInput(stage.stdInFile != null
                ? ProcessBuilder.Redirect.from(stage.stdInFile)
                : ProcessBuilder.Redirect.INHERIT);
        } else {
            b.redirectInput(ProcessBuilder.Redirect.PIPE);
        }

        // stdout: last stage to terminal/file; otherwise pump into connector.
        if (idx == n - 1) {
            if (stage.stdOutFile != null) {
                b.redirectOutput(stage.appendStdOut
                    ? ProcessBuilder.Redirect.appendTo(stage.stdOutFile)
                    : ProcessBuilder.Redirect.to(stage.stdOutFile));
            } else {
                b.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
        } else {
            b.redirectOutput(ProcessBuilder.Redirect.PIPE);
        }

        // stderr: own 2> file, else the terminal.
        if (stage.stdErrFile != null) {
            b.redirectError(stage.appendStdErr
                ? ProcessBuilder.Redirect.appendTo(stage.stdErrFile)
                : ProcessBuilder.Redirect.to(stage.stdErrFile));
        } else {
            b.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        Process process = b.start();

        // Feed the process from the upstream connector on a side thread (it can
        // block), closing the process's stdin at EOF so the process can finish.
        Thread inPump = null;
        if (idx != 0) {
            final InputStream src = pipeIn;
            final OutputStream dst = process.getOutputStream();
            inPump = new Thread(() -> pump(src, dst, true));
            inPump.start();
        }

        // Copy the process's stdout into the downstream connector inline; this
        // returns at EOF (process closed stdout). Don't close pipeOut here — the
        // worker thread's finally does that to signal EOF downstream.
        if (idx != n - 1) {
            pump(process.getInputStream(), pipeOut, false);
        }

        process.waitFor();
        if (inPump != null) inPump.join();
    }

    /**
     * Copies all bytes from {@code from} to {@code to}, flushing at the end.
     * IOExceptions (e.g. a broken pipe when the reader has gone away) are
     * swallowed — in a pipeline that just means the downstream stage exited.
     *
     * @param closeTo whether to close {@code to} once copying finishes
     */
    static void pump(InputStream from, OutputStream to, boolean closeTo) {
        byte[] buf = new byte[8192];
        try {
            int r;
            while ((r = from.read(buf)) != -1) {
                to.write(buf, 0, r);
                to.flush();
            }
            to.flush();
        } catch (IOException ignore) {
            // broken pipe / closed stream — nothing useful to do
        } finally {
            if (closeTo) {
                try { to.close(); } catch (IOException ignore) {}
            }
        }
    }

    /**
     * Tokenizes a raw input line respecting single quotes, double quotes, and backslash escapes.
     *
     * Rules (matching typical POSIX shell behaviour):
     *   - Outside any quotes: backslash escapes the next character literally.
     *   - Inside double quotes: only \" and \\ are treated as escapes; other
     *     backslash sequences are passed through unchanged.
     *   - Inside single quotes: everything is literal; no escape processing.
     *   - Unquoted spaces delimit tokens; consecutive spaces produce a single split.
     *
     * @param input  the raw line read from stdin (leading/trailing whitespace already trimmed)
     * @return       array of tokens; never null, may be empty
     */
    static String[] parseInput(String input) {
        ArrayList<String> parsed = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean insideSingleQuotes = false;
        boolean insideDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // Backslash handling — only active outside single quotes
            if (c == '\\' && i + 1 < input.length() && !insideSingleQuotes) {
                char nextChar = input.charAt(i + 1);
                // Inside double quotes only \" and \\ are special escapes
                boolean escapesInsideDoubleQuotes = nextChar == '"' || nextChar == '\\';

                if (!insideDoubleQuotes) {
                    // Bare backslash outside any quotes: consume it and take next char literally
                    current.append(nextChar);
                    i++;
                    continue;
                } else if (escapesInsideDoubleQuotes) {
                    // Inside double quotes, recognised escape sequences collapse
                    current.append(nextChar);
                    i++;
                    continue;
                }
                // Inside double quotes but not a recognised escape: keep the backslash too
                current.append(nextChar);
                i++;
                continue;
            }

            // Toggle single-quote mode (only when not already inside double quotes)
            if (c == '\'' && !insideDoubleQuotes) {
                insideSingleQuotes = !insideSingleQuotes;
                continue;
            }

            // Toggle double-quote mode (only when not already inside single quotes)
            if (c == '"' && !insideSingleQuotes) {
                insideDoubleQuotes = !insideDoubleQuotes;
                continue;
            }

            // Unquoted pipe: emit "|" as its own token so the pipeline
            // splitter can find stage boundaries (handles "a|b" with no spaces).
            if (c == '|' && !insideDoubleQuotes && !insideSingleQuotes) {
                if (current.length() > 0) {
                    parsed.add(current.toString());
                    current.setLength(0);
                }
                parsed.add("|");
                continue;
            }

            // Unquoted space: flush the current token and start a new one
            if (c == ' ' && !insideDoubleQuotes && !insideSingleQuotes) {
                if (current.length() > 0) {
                    parsed.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        // Flush the last token if there is one (handles input with no trailing space)
        if (current.length() > 0) {
            parsed.add(current.toString());
        }

        return parsed.toArray(new String[0]);
    }

    /**
     * Scans a token array for I/O redirection operators and separates them from the command.
     *
     * Recognised operators:
     *   ">"  or "1>"  — redirect stdout to the following token (file name)
     *   "2>"           — redirect stderr to the following token (file name)
     *
     * The command tokens that precede the first redirection operator are
     * placed in the returned ParsedCommand.parts; the redirection targets
     * populate the corresponding File fields.
     *
     * If no redirection is found, parts is left unchanged and all File fields are null.
     *
     * @param parts  tokenized input produced by parseInput()
     * @return       a ParsedCommand with command tokens and optional redirection files
     */
    static ParsedCommand parseRedirection(String[] parts) {
        File stdErrFile = null;
        File stdInFile = null;
        File stdOutFile = null;
        boolean appendStdOut = false;
        boolean appendStdErr = false;
        String[] commandParts = parts;

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(">") || parts[i].equals("1>")) {
                // Guard: off-by-one — need at least one token after the operator
                if (i + 1 >= parts.length) {
                    System.out.println("Syntax error: redirection operator without file");
                    ParsedCommand pc = new ParsedCommand(parts, stdOutFile, stdErrFile, stdInFile);
                    pc.appendStdOut = appendStdOut;
                    pc.appendStdErr = appendStdErr;
                    return pc;
                }
                // Everything before the first redirection operator is the real command
                commandParts = Arrays.copyOfRange(parts, 0, i);
                stdOutFile = new File(parts[i + 1]);
                i++; // skip the filename token on the next iteration
            } else if (parts[i].equals("2>")) {
                // Do NOT re-slice commandParts here — it was already set correctly
                // by the ">" branch (or stays as `parts` if only stderr is redirected)
                if (i + 1 >= parts.length) {
                    System.out.println("Syntax error: redirection operator without file");
                    ParsedCommand pc = new ParsedCommand(parts, stdOutFile, stdErrFile, stdInFile);
                    pc.appendStdOut = appendStdOut;
                    pc.appendStdErr = appendStdErr;
                    return pc;
                }
                if (commandParts == parts) {
                    // Standalone 2> with no preceding stdout redirect
                    commandParts = Arrays.copyOfRange(parts, 0, i);
                }
                stdErrFile = new File(parts[i + 1]);
                i++; // skip the filename token on the next iteration
            }
            else if (parts[i].equals(">>") || parts[i].equals("1>>")) {
                if (i + 1 >= parts.length) {
                    System.out.println("Syntax error: redirection operator without file");
                    ParsedCommand pc = new ParsedCommand(parts, stdOutFile, stdErrFile, stdInFile);
                    pc.appendStdOut = appendStdOut;
                    pc.appendStdErr = appendStdErr;
                    return pc;
                }
                commandParts = Arrays.copyOfRange(parts, 0, i);
                stdOutFile = new File(parts[i + 1]);
                appendStdOut = true;
                i++;
            } else if (parts[i].equals("2>>")) {
                if (i + 1 >= parts.length) {
                    System.out.println("Syntax error: redirection operator without file");
                    ParsedCommand pc = new ParsedCommand(parts, stdOutFile, stdErrFile, stdInFile);
                    pc.appendStdOut = appendStdOut;
                    pc.appendStdErr = appendStdErr;
                    return pc;
                }
                if (commandParts == parts) {
                    commandParts = Arrays.copyOfRange(parts, 0, i);
                }
                stdErrFile = new File(parts[i + 1]);
                appendStdErr = true;
                i++;
            }
        }
        

        // Return whatever was collected — if no operators were found,
        // commandParts == parts and all File fields are null (no redirection)
        ParsedCommand pc = new ParsedCommand(commandParts, stdOutFile, stdErrFile, stdInFile);
        pc.appendStdOut = appendStdOut;
        pc.appendStdErr = appendStdErr;
        return pc;
    }



    static String formatJobLine(Job job, int index) {
        String status = job.process.isAlive() ? "Running" : "Done";
        job.status = status;
        String statusColumn = String.format("%-24s", status);
        String jobId = String.format("[%d]", job.id);
        String marker = " ";

        if (index == Job.jobs.size() - 1) {
            marker = "+";
        } else if (index == Job.jobs.size() - 2) {
            marker = "-";
        }

        String jobIdColumn = String.format("%-6s", jobId + marker);
        String commandStr = job.status.equals("Running") ? job.command + " &" : job.command;
        return jobIdColumn + statusColumn + commandStr;
    }

    static class JobsCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            for (int index = 0; index < Job.jobs.size(); index++) {
                Job job = Job.jobs.get(index);
                out.println(formatJobLine(job, index));

                // If the job is done, remove it from the list
                if (job.status.equals("Done")) {
                    Job.jobs.remove(index);
                    index--; // Adjust index after removal
                }
            }
        }
    }


    static void checkJobs() {
        for (int index = 0; index < Job.jobs.size(); index++) {
            Job job = Job.jobs.get(index);
            if (!job.process.isAlive() && job.status.equals("Running")) {
                System.err.println(formatJobLine(job, index));
                Job.jobs.remove(index);
                index--;
            }
        }
    }

    /**
     * Shell REPL — Read, Evaluate, Print, Loop.
     *
     * Lifecycle:
     *   1. Print the "$ " prompt.
     *   2. Read one line from stdin.
     *   3. Tokenize it with parseInput(), then detect redirections with parseRedirection().
     *   4. Dispatch to the matching built-in or external executor.
     *   5. Repeat until "exit" is entered.
     *
     * stdout and stderr PrintStream references are updated each iteration
     * when redirection is active, so that built-ins writing to `out` or `err`
     * write to the correct destination.
     */
    public static void main(String[] args) throws Exception {
        Scanner in = new Scanner(System.in);
        String input = "";

        // Per-command output streams — reset each iteration from stdOutFile/stdErrFile
        PrintStream out = System.out;
        PrintStream err = System.err;

        while (true) {
            checkJobs();
            System.out.print("$ ");
            input = in.nextLine().trim();

            // Skip blank lines without printing an error
            if (input.isEmpty()) {
                continue;
            }

            // Step 1: split raw input into tokens, honouring quoting rules
            boolean backgroundExecution = false;
            String[] rawParts = parseInput(input);
            if(rawParts[rawParts.length - 1].equals("&")) {
                backgroundExecution = true;
                // This can happen if the user enters only redirection operators with no command
                rawParts = Arrays.copyOf(rawParts, rawParts.length - 1);
            }

            // Step 1.5: if the line contains "|", it's a pipeline. Split into
            // stages, parse each stage's own redirections, and run them
            // concurrently. Pipelines bypass the single-command dispatch below.
            boolean hasPipe = false;
            for (String p : rawParts) {
                if (p.equals("|")) { hasPipe = true; break; }
            }
            if (hasPipe) {
                List<ParsedCommand> stages = new ArrayList<>();
                for (String[] segment : splitPipeline(rawParts)) {
                    stages.add(parseRedirection(segment));
                }
                executePipeline(stages);
                continue;
            }

            // Step 2: strip redirection operators and collect target files
            ParsedCommand command = parseRedirection(rawParts);


            command.backgroundExecution = backgroundExecution;
            String[] parts = command.parts;
            File stdOutFile = command.stdOutFile;
            File stdErrFile = command.stdErrFile;

            // Reset to terminal streams each iteration so redirection doesn't bleed across commands
            
            try{
                out = System.out;
                err = System.err;
                if (stdOutFile != null) {
                    out = new PrintStream(new FileOutputStream(stdOutFile, command.appendStdOut));
                }
                if (stdErrFile != null) {
                    err = new PrintStream(new FileOutputStream(stdErrFile, command.appendStdErr));
                }

            } catch(java.io.FileNotFoundException e) {
                System.err.println("Redirection error: " + e.getMessage());
                // If the redirection target can't be opened, skip dispatch but continue the REPL
                continue;

            }
            

            // Step 3: dispatch
            //
            // `exit` is handled first because it controls the REPL lifecycle.
            // It is matched against the raw line so it works even if the user
            // types extra whitespace (already trimmed above).
            if (input.equals("exit") || input.equals("exit 0")) {
                in.close();
                System.exit(0);
            }

            // Built-ins: a single registry lookup replaces the old if/else chain.
            // The current per-command streams are passed in so redirection (and,
            // later, pipelines) works without the built-ins knowing about it.
            BuiltinCommand builtin = builtins.get(parts[0]);
            if (builtin != null) {
                builtin.execute(command, System.in, out, err);
            } else if (findExecutableInPath(parts[0]) != null) {
                // Not a built-in — try to launch as an external process
                executeCommand(command);
            } else {
                System.out.println(input + ": command not found");
            }
        }
    }
}