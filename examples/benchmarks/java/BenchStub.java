import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Is here to compare us to a java native no coffi path.
 * The jextract pattern: a downcall handle in a static final field of a
 * runtime-initialized class, invoked with exact types from the same class.
 * Measures whether GraalVM native-image can constant-fold this into a direct
 * stub call, as opposed to the method-handle simulation coffi's
 * instance-field design gets.
 */
public final class BenchStub {

    private static final MethodHandle ACK;

    static {
        try {
            SymbolLookup lib = SymbolLookup.libraryLookup("native/libbench.so", Arena.global());
            ACK = Linker.nativeLinker().downcallHandle(
                lib.find("ack").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    public static long ack(long m, long n) {
        try {
            return (long) ACK.invokeExact(m, n);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
