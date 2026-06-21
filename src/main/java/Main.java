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

public class Main {

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

    static Path currentDirectory = Path.of(System.getProperty("user.dir"));

    static Set<String> commands = new HashSet<>(
        Arrays.asList("echo", "type", "exit", "pwd", "cd", "jobs")
    );

    interface BuiltinCommand {
        void execute(ParsedCommand cmd, InputStream in, PrintStream out, PrintStream err) throws Exception;
    }

    static Map<String, BuiltinCommand> builtins = new HashMap<>();
    static {
        builtins.put("echo", new EchoCommand());
        builtins.put("pwd", new PwdCommand());
        builtins.put("type", new TypeCommand());
        builtins.put("cd", new CdCommand());
        builtins.put("jobs", new JobsCommand());
    }

    static class EchoCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            String[] parts = command.parts;
            if (parts.length == 1) {

                out.println("");
            } else {
                for (int i = 1; i < parts.length; i++) {
                    if (i == parts.length - 1) {

                        out.print(parts[i]);
                        break;
                    }
                    out.print(parts[i] + " ");
                }
                out.println();
            }
        }
    }

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

            Path executablePath = findExecutableInPath(target);
            if (executablePath != null) {
                out.println(target + " is " + executablePath);
            } else {
                out.println(target + ": not found");
            }
        }
    }

    static Path findExecutableInPath(String command) {
        String PATH = System.getenv("PATH");

        String[] directories = PATH.split(File.pathSeparator);

        for (String directory : directories) {
            Path filePath = Path.of(directory, command);
            if (Files.isRegularFile(filePath) && Files.isExecutable(filePath)) {
                return filePath;
            }
        }
        return null;
    }

    static class PwdCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            out.println(currentDirectory);
        }
    }

    static class CdCommand implements BuiltinCommand {
        public void execute(ParsedCommand command, InputStream in, PrintStream out, PrintStream err) {
            String[] parts = command.parts;
            Path target;
            String home = System.getenv("HOME");
            Path homePath = Path.of(home);

            if (parts.length <= 1 || (parts[1].startsWith("~") && parts[1].length() == 1)) {

                currentDirectory = homePath;
                return;
            } else if (parts[1].startsWith("~/")) {

                String rem = parts[1].substring(2);
                target = homePath.resolve(rem);
            } else {
                Path destination = Path.of(parts[1]);

                if (destination.isAbsolute()) {
                    target = destination;
                } else {

                    target = currentDirectory.resolve(destination);
                }
            }

            target = target.normalize();

            if (Files.isDirectory(target)) {
                currentDirectory = target;
            } else {

                err.println("cd: " + parts[1] + ": No such file or directory");
            }
        }
    }

    static void executeCommand(ParsedCommand command) throws Exception {
        String[] parts = command.parts;
        File stdOutFile = command.stdOutFile;
        File stdErrFile = command.stdErrFile;
        File stdInFile = command.stdInFile;
        boolean backgroundExecution = command.backgroundExecution;

        ProcessBuilder builder = new ProcessBuilder(parts);
        builder.directory(currentDirectory.toFile());

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

            System.err.println(parts[0] + ": " + e.getMessage());
        }
    }

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

    static void executePipeline(List<ParsedCommand> stages) throws Exception {
        final int n = stages.size();

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

    static void runStage(ParsedCommand stage, int idx, int n,
                         InputStream pipeIn, OutputStream pipeOut) throws Exception {
        BuiltinCommand builtin = builtins.get(stage.parts[0]);

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

        if (findExecutableInPath(stage.parts[0]) == null) {
            System.err.println(stage.parts[0] + ": command not found");

            if (pipeIn != null) pump(pipeIn, OutputStream.nullOutputStream(), false);
            return;
        }

        ProcessBuilder b = new ProcessBuilder(stage.parts);
        b.directory(currentDirectory.toFile());

        if (idx == 0) {
            b.redirectInput(stage.stdInFile != null
                ? ProcessBuilder.Redirect.from(stage.stdInFile)
                : ProcessBuilder.Redirect.INHERIT);
        } else {
            b.redirectInput(ProcessBuilder.Redirect.PIPE);
        }

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

        if (stage.stdErrFile != null) {
            b.redirectError(stage.appendStdErr
                ? ProcessBuilder.Redirect.appendTo(stage.stdErrFile)
                : ProcessBuilder.Redirect.to(stage.stdErrFile));
        } else {
            b.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        Process process = b.start();

        Thread inPump = null;
        if (idx != 0) {
            final InputStream src = pipeIn;
            final OutputStream dst = process.getOutputStream();
            inPump = new Thread(() -> pump(src, dst, true));
            inPump.start();
        }

        if (idx != n - 1) {
            pump(process.getInputStream(), pipeOut, false);
        }

        process.waitFor();
        if (inPump != null) inPump.join();
    }

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

        } finally {
            if (closeTo) {
                try { to.close(); } catch (IOException ignore) {}
            }
        }
    }

    static String[] parseInput(String input) {
        ArrayList<String> parsed = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean insideSingleQuotes = false;
        boolean insideDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && i + 1 < input.length() && !insideSingleQuotes) {
                char nextChar = input.charAt(i + 1);

                boolean escapesInsideDoubleQuotes = nextChar == '"' || nextChar == '\\';

                if (!insideDoubleQuotes) {

                    current.append(nextChar);
                    i++;
                    continue;
                } else if (escapesInsideDoubleQuotes) {

                    current.append(nextChar);
                    i++;
                    continue;
                }

                current.append(nextChar);
                i++;
                continue;
            }

            if (c == '\'' && !insideDoubleQuotes) {
                insideSingleQuotes = !insideSingleQuotes;
                continue;
            }

            if (c == '"' && !insideSingleQuotes) {
                insideDoubleQuotes = !insideDoubleQuotes;
                continue;
            }

            if (c == '|' && !insideDoubleQuotes && !insideSingleQuotes) {
                if (current.length() > 0) {
                    parsed.add(current.toString());
                    current.setLength(0);
                }
                parsed.add("|");
                continue;
            }

            if (c == ' ' && !insideDoubleQuotes && !insideSingleQuotes) {
                if (current.length() > 0) {
                    parsed.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parsed.add(current.toString());
        }

        return parsed.toArray(new String[0]);
    }

    static ParsedCommand parseRedirection(String[] parts) {
        File stdErrFile = null;
        File stdInFile = null;
        File stdOutFile = null;
        boolean appendStdOut = false;
        boolean appendStdErr = false;
        String[] commandParts = parts;

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(">") || parts[i].equals("1>")) {

                if (i + 1 >= parts.length) {
                    System.out.println("Syntax error: redirection operator without file");
                    ParsedCommand pc = new ParsedCommand(parts, stdOutFile, stdErrFile, stdInFile);
                    pc.appendStdOut = appendStdOut;
                    pc.appendStdErr = appendStdErr;
                    return pc;
                }

                commandParts = Arrays.copyOfRange(parts, 0, i);
                stdOutFile = new File(parts[i + 1]);
                i++;
            } else if (parts[i].equals("2>")) {

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
                i++;
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

                if (job.status.equals("Done")) {
                    Job.jobs.remove(index);
                    index--;
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

    public static void main(String[] args) throws Exception {
        Scanner in = new Scanner(System.in);
        String input = "";

        PrintStream out = System.out;
        PrintStream err = System.err;

        while (true) {
            checkJobs();
            System.out.print("$ ");
            input = in.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            boolean backgroundExecution = false;
            String[] rawParts = parseInput(input);
            if(rawParts[rawParts.length - 1].equals("&")) {
                backgroundExecution = true;

                rawParts = Arrays.copyOf(rawParts, rawParts.length - 1);
            }

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

            ParsedCommand command = parseRedirection(rawParts);

            command.backgroundExecution = backgroundExecution;
            String[] parts = command.parts;
            File stdOutFile = command.stdOutFile;
            File stdErrFile = command.stdErrFile;

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

                continue;

            }

            if (input.equals("exit") || input.equals("exit 0")) {
                in.close();
                System.exit(0);
            }

            BuiltinCommand builtin = builtins.get(parts[0]);
            if (builtin != null) {
                builtin.execute(command, System.in, out, err);
            } else if (findExecutableInPath(parts[0]) != null) {

                executeCommand(command);
            } else {
                System.out.println(input + ": command not found");
            }
        }
    }
}
