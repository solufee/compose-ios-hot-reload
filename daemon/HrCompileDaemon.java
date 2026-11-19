import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent Kotlin/Native compiler for hot reload.
 *
 * Keep the compiler classes loaded and the JVM warm; each request is one
 * compiler invocation over a Unix domain socket. Protocol: request = one
 * argument per line, terminated by an empty line; response = "OK <ms>" or
 * "ERR <exitCode> <ms>", then the connection closes.
 *
 * Tool request (interface v8): if the first line is "@klib", the second line
 * is an output file and the rest are arguments of the `klib` tool (for
 * example "dump-metadata", "<path>.klib"). The tool runs in this JVM through
 * its public org.jetbrains.kotlin.cli.klib.Main.exec(stdout, stderr, args),
 * which returns an exit code instead of exiting the process. Its stdout goes
 * to the output file, its stderr to the daemon log.
 */
public class HrCompileDaemon {
    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String socketPath = args[1];

        Path sock = Path.of(socketPath);
        Files.deleteIfExists(sock);
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock));
        System.out.println("READY " + socketPath + " pid=" + ProcessHandle.current().pid());
        System.out.flush();

        URLClassLoader cl = new URLClassLoader(new URL[]{ new File(jar).toURI().toURL() },
                HrCompileDaemon.class.getClassLoader());
        Class<?> k2 = cl.loadClass("org.jetbrains.kotlin.cli.bc.K2Native");
        Object compiler = k2.getConstructor().newInstance();
        Class<?> base = cl.loadClass("org.jetbrains.kotlin.cli.common.CLICompiler");
        Method invoke = base.getMethod("doMainNoExit", base, String[].class);
        Method klibExec = null;  // loaded on the first @klib request

        while (true) {
            try (SocketChannel ch = server.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                Writer out = new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8);
                List<String> argv = new ArrayList<>();
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) argv.add(line);

                long t = System.nanoTime();
                String response;
                if (argv.size() >= 2 && "@klib".equals(argv.get(0))) {
                    try {
                        if (klibExec == null) {
                            Class<?> km = cl.loadClass("org.jetbrains.kotlin.cli.klib.Main");
                            klibExec = km.getMethod("exec", Appendable.class, Appendable.class, String[].class);
                        }
                        String[] kargs = argv.subList(2, argv.size()).toArray(new String[0]);
                        StringBuilder err = new StringBuilder();
                        Object code;
                        try (Writer w = Files.newBufferedWriter(Path.of(argv.get(1)), StandardCharsets.UTF_8)) {
                            code = klibExec.invoke(null, w, err, (Object) kargs);
                        }
                        if (err.length() > 0) System.out.print(err);
                        long ms = Math.round((System.nanoTime() - t) / 1e6);
                        response = Integer.valueOf(0).equals(code) ? "OK " + ms : "ERR " + code + " " + ms;
                    } catch (Throwable e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        cause.printStackTrace();
                        long ms = Math.round((System.nanoTime() - t) / 1e6);
                        response = "ERR exception " + ms;
                    }
                    out.write(response + "\n");
                    out.flush();
                    System.out.println("KLIB " + argv.get(2) + " -> " + response);
                    System.out.flush();
                    continue;
                }
                try {
                    Object code = invoke.invoke(null, compiler, (Object) argv.toArray(new String[0]));
                    long ms = Math.round((System.nanoTime() - t) / 1e6);
                    response = ("OK".equals(String.valueOf(code)) || "ExitCode.OK".equals(String.valueOf(code)))
                            ? "OK " + ms : "ERR " + code + " " + ms;
                } catch (Throwable e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    cause.printStackTrace();
                    long ms = Math.round((System.nanoTime() - t) / 1e6);
                    response = "ERR exception " + ms;
                }
                out.write(response + "\n");
                out.flush();
                System.out.println("REQ " + argv.size() + " args -> " + response);
                System.out.flush();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}
