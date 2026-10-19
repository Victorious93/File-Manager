package ai.victorious.victorfiles.priv;

// Runs inside the Shizuku/Sui-spawned process (shell or root uid). Kept tiny on purpose:
// the app side builds validated commands (core PowerCommands / ShellCommands).
interface IPrivilegedService {
    // Shizuku-reserved transaction code for UserService teardown (per Shizuku-API docs).
    void destroy() = 16777114;

    void exit() = 1;

    // Returns [exitCode, stdout, stderr]. stdin may be null.
    String[] exec(String command, String stdin, long timeoutSec) = 2;
}
