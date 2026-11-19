import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Calls the Kotlin/Native CLI several times in one JVM; edits BUILD_TAG before each run. */
public class WarmK2 {
    public static void main(String[] args) throws Exception {
        String jar = args[0];
        int runs = Integer.parseInt(args[1]);
        String tagPrefix = args[2];
        String source = args[3];
        List<String> cli = new ArrayList<>();
        for (int i = 4; i < args.length; i++) cli.add(args[i]);

        URLClassLoader cl = new URLClassLoader(new URL[]{ new File(jar).toURI().toURL() },
                WarmK2.class.getClassLoader());
        Class<?> k2 = cl.loadClass("org.jetbrains.kotlin.cli.bc.K2Native");
        Object instance = k2.getConstructor().newInstance();
        Class<?> base = cl.loadClass("org.jetbrains.kotlin.cli.common.CLICompiler");
        Method m = base.getMethod("doMainNoExit", base, String[].class);
        String[] argv = cli.toArray(new String[0]);
        Path src = Path.of(source);
        for (int i = 0; i < runs; i++) {
            String text = Files.readString(src);
            text = text.replaceAll("private const val BUILD_TAG = \"[^\"]*\"",
                    "private const val BUILD_TAG = \"" + tagPrefix + i + "\"");
            Files.writeString(src, text);
            long t = System.nanoTime();
            Object code = m.invoke(null, instance, argv);
            System.out.printf("RUN %d %.0f ms exit=%s%n", i, (System.nanoTime() - t) / 1e6, code);
        }
    }
}
