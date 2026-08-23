package android.util;

/**
 * Minimal stand-in for {@code android.util.Log} in plain JVM unit tests.
 *
 * <p>Deliberately hand-rolled rather than mocked: with {@code returnDefaultValues} left off, any
 * android.jar call this class does not cover fails loudly with {@code NoSuchMethodError} instead of
 * silently returning 0. That is the point — it caught production code calling a three-argument
 * overload that did not exist here, rather than letting the call quietly disappear.
 *
 * <p>So add overloads when a real one is needed, and do not replace this with a permissive mock.
 */
public class Log {

  public static int d(String tag, String msg) {
    return print("DEBUG", tag, msg, null);
  }

  public static int d(String tag, String msg, Throwable t) {
    return print("DEBUG", tag, msg, t);
  }

  public static int i(String tag, String msg) {
    return print("INFO", tag, msg, null);
  }

  public static int i(String tag, String msg, Throwable t) {
    return print("INFO", tag, msg, t);
  }

  public static int w(String tag, String msg) {
    return print("WARN", tag, msg, null);
  }

  public static int w(String tag, String msg, Throwable t) {
    return print("WARN", tag, msg, t);
  }

  public static int w(String tag, Throwable t) {
    return print("WARN", tag, t == null ? "" : t.toString(), t);
  }

  public static int e(String tag, String msg) {
    return print("ERROR", tag, msg, null);
  }

  public static int e(String tag, String msg, Throwable t) {
    return print("ERROR", tag, msg, t);
  }

  public static int v(String tag, String msg) {
    return print("VERBOSE", tag, msg, null);
  }

  public static int v(String tag, String msg, Throwable t) {
    return print("VERBOSE", tag, msg, t);
  }

  private static int print(String level, String tag, String msg, Throwable t) {
    System.out.println(level + ": " + tag + ": " + msg
        + (t == null ? "" : " (" + t + ")"));
    return 0;
  }
}
