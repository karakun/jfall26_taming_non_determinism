package advisor;

/** A reason the app can't start, in one line the user can act on. */
public class StartupException extends RuntimeException {

    public StartupException(String message) {
        super(message);
    }
}
