package com.aap.gitst;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Andrey Pavlenko
 */
public class Git {
    private static final boolean NATIVE_LIB_LOADED;
    private static final String FILE_MARKS = ".marks";
    private final File _gitDir;
    private final File _repoDir;
    private final String _executable;

    static {
        boolean loaded;

        try {
            System.loadLibrary("git-st");
            loaded = true;
        } catch (final UnsatisfiedLinkError ex) {
            loaded = false;
        }

        NATIVE_LIB_LOADED = loaded;
    }

    public Git() throws InterruptedException, IOException, ExecutionException {
        this(null);
    }

    public Git(final File repoDir) throws InterruptedException, IOException,
            ExecutionException {
        this(repoDir, null);
    }

    public Git(final File repoDir, final File gitDir)
            throws InterruptedException, IOException, ExecutionException {
        this(repoDir, gitDir, null);
    }

    public Git(File repoDir, File gitDir, String executable)
            throws InterruptedException, IOException, ExecutionException {
        if (executable == null) {
            executable = "git";
        }
        if (gitDir == null) {
            Exec exec = new Exec(repoDir == null ? new File(".") : repoDir,
                    executable, "rev-parse", "--git-dir");
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            exec.setOutStream(baos);
            final int exit = exec.exec().waitFor();

            if (exit != 0) {
                throw new ExecutionException(executable
                        + " rev-parse --git-dir failed", exit);
            }

            String dir = baos.toString("UTF-8").trim();

            if ("cygwin".equalsIgnoreCase(System.getenv("OSTYPE"))) {
                exec = new Exec(new File("."), "cygpath", "-m", dir);
                baos.reset();
                exec.setOutStream(baos);
                exec.setErrStream(null);

                if (exec.exec().waitFor() == 0) {
                    dir = baos.toString("UTF-8").trim();
                }
            }

            gitDir = new File(dir);

            if (!gitDir.isAbsolute()) {
                gitDir = new File(exec.getProcessBuilder().directory(), dir);
            }
        }
        if (repoDir == null) {
            final Exec exec = new Exec(new File("."), executable, "config",
                    "core.bare");
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            exec.setOutStream(baos);
            final int exit = exec.exec().waitFor();

            if (exit != 0) {
                throw new ExecutionException(executable
                        + " config core.bare failed", exit);
            }

            repoDir = Boolean.parseBoolean(baos.toString("UTF-8").trim())
                    ? gitDir : gitDir.getParentFile();
        }

        _gitDir = gitDir;
        _repoDir = repoDir;
        _executable = executable;
    }

    public File getRepoDir() {
        return _repoDir;
    }

    public File getGitDir() {
        return _gitDir;
    }

    public String getExecutable() {
        return _executable;
    }

    public String getOption(final String name) throws IOException {
        final Exec exec = exec("config", name);
        exec.setOutStream(null);
        final Process proc = exec.exec().getProcess();

        try {
            final BufferedReader r = new BufferedReader(new InputStreamReader(
                    proc.getInputStream()));
            return r.readLine();
        } finally {
            proc.destroy();
        }
    }

    public Exec exec(final String arg, final String... args) {
        if (args != null) {
            final List<String> l = new ArrayList<>(args.length + 1);
            l.add(arg);

            for (final String a : args) {
                l.add(a);
            }

            return exec(l);
        } else {
            return exec(Collections.singletonList(arg));
        }
    }

    public Exec exec(final List<String> args) {
        final List<String> command = new ArrayList<>(args.size() + 1);
        command.add(getExecutable());
        command.addAll(args);
        return new Exec(getRepoDir(), command);
    }

    public Exec fastImport(final String branch, final String... args) {
        final List<String> l = new ArrayList<>(args == null ? 1
                : 1 + args.length);
        l.add("fast-import");

        if (args != null) {
            for (final String a : args) {
                l.add(a);
            }
        }

        return exec(l);
    }

    public Exec fastExport(final String branch, final File exportMarks,
            final String... args) {
        final List<String> l = new ArrayList<>(args == null ? 6
                : 6 + args.length);
        final File importMarks = getMarksFile(branch);
        Exec exec;

        l.add("fast-export");
        l.add("--no-data");
        l.add("-M");
        l.add("--export-marks=" + exportMarks.getAbsolutePath());

        if (importMarks.exists()) {
            l.add("--import-marks=" + importMarks.getAbsolutePath());
        }
        if (args != null) {
            for (final String a : args) {
                l.add(a);
            }
        }

        l.add(branch);
        exec = exec(l);
        exec.setOutStream(null);
        return exec;
    }

    public InputStream catFile(final String sha) throws IOException {
        final Exec exec = exec("cat-file", "blob", sha);
        exec.setOutStream(null);
        return exec.exec().getProcess().getInputStream();
    }

    public File catFile(final String sha, final File toFile) throws IOException {
        final byte[] buff = new byte[8192];

        try (InputStream in = catFile(sha);
                OutputStream out = new FileOutputStream(toFile)) {
            for (int i = in.read(buff); i != -1; i = in.read(buff)) {
                out.write(buff, 0, i);
            }
        }

        return toFile;
    }

    public boolean containsPath(final String branch, final String path)
            throws InterruptedException, IOException {
        final Exec exec = exec("ls-tree", "--name-only", branch + ':' + path);
        exec.setErrStream(null);
        exec.setOutStream(null);
        return exec.exec().waitFor() == 0;
    }

    public Exec checkout(final String... args) {
        return exec("checkout", args);
    }

    public String showRef(final String ref) throws InterruptedException,
            IOException {
        final Exec exec = new Exec(getRepoDir(), "git", "show-ref", "--verify",
                "-s", ref);
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        exec.setErrStream(null);
        exec.setOutStream(baos);
        final int exit = exec.exec().waitFor();
        return (exit == 0) ? new String(baos.toByteArray()).trim() : null;
    }

    public void updateRef(final String ref, final String sha)
            throws InterruptedException, IOException, ExecutionException {
        Exec exec = new Exec(getRepoDir(), "git", "update-ref", ref, sha);
        exec.setErrStream(null);
        exec.setOutStream(null);
        final int exit = exec.exec().waitFor();

        if (exit != 0) {
            exec = new Exec(getRepoDir(), "git", "show-ref", ref);
            exec.setErrStream(null);
            exec.setOutStream(null);

            if (exec.exec().waitFor() != 0) {
                // Ref does not exists
                final File f = new File(getGitDir(), ref);
                f.getParentFile().mkdirs();

                try (PrintStream s = new PrintStream(f)) {
                    s.print(sha);
                }
                if (new Exec(getRepoDir(), "git", "update-ref", ref, sha)
                        .waitFor() == 0) {
                    return;
                }
            }

            throw new ExecutionException("Failed to execute: git update-ref "
                    + ref + ' ' + sha, exit);
        }
    }

    public File getMarksFile(final String ref) {
        final File repoDir = getRepoDir();
        File gitDir = new File(getRepoDir(), ".git");

        if (!gitDir.isDirectory()) {
            // Bare repository
            gitDir = repoDir;
        }

        return new File(new File(new File(gitDir, "git-st"), ref), FILE_MARKS);
    }

    public Marks loadMarks(final String ref) throws IOException {
        final Marks marks = new Marks();
        final File f = getMarksFile(ref);
        return f.isFile() ? marks.load(f) : marks;
    }

    public Set<String> listIgnored() throws InterruptedException, IOException {
        final Set<String> ignores = new HashSet<>();
        final Exec exec = exec("ls-files", "-i", "-o", "--exclude-standard",
                "--directory");
        exec.setOutStream(null);
        final Process proc = exec.exec().getProcess();

        try {
            final BufferedReader r = new BufferedReader(new InputStreamReader(
                    proc.getInputStream()));

            for (String s = r.readLine(); s != null; s = r.readLine()) {
                ignores.add(s.trim());
            }

            exec.waitFor();
        } finally {
            proc.destroy();
        }

        return ignores;
    }

    public CredentialHelper getCredentialHelper(final String protocol,
            final String host, final String user) {
        if (NATIVE_LIB_LOADED) {
            return new CHelper(protocol, host, user);
        }

        return null;
    }

    private static native String[] getCredentials(CredentialCallBack cb,
            String protocol, String host, final String user);

    private static final class CHelper implements CredentialHelper {
        private final String _protocol;
        private final String _host;
        private final String _user;

        public CHelper(final String protocol, final String host,
                final String user) {
            _protocol = protocol;
            _host = host;
            _user = user;
        }

        @Override
        public void getCredentials(final CredentialCallBack cb) {
            Git.getCredentials(cb, _protocol, _host, _user);
        }
    }
}
