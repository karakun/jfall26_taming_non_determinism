package replay;

/** A problem that stops the replay from starting, explained in one line. */
public class StartupException extends RuntimeException {

    public StartupException(String message) {
        super(message);
    }
}
