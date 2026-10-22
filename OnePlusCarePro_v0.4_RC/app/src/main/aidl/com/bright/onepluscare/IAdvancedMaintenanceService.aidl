package com.bright.onepluscare;

/**
 * Optional Shizuku UserService (shell or root identity).
 * There is deliberately no method that accepts a command line, path or argument list:
 *  - readProperty accepts only keys on AdvancedAllowList.PROPERTIES;
 *  - runDiagnostic accepts only an AdvancedDiagnostic enum name with a fixed argv;
 *  - runOp accepts a JSON-encoded core TweakOp, which the service re-validates with
 *    TweakCommands.argvFor() (allow-listed settings, app-ops, packages, density) before running it.
 */
interface IAdvancedMaintenanceService {
    String identity() = 1;
    String readProperty(String key) = 2;
    String runDiagnostic(String diagnosticId) = 3;
    String runOp(String opJson) = 4;
    void destroy() = 16777114;
}
