package com.bright.onepluscare;

/**
 * Read-only diagnostics executed by the optional Shizuku UserService (shell or root identity).
 * There is deliberately no method that accepts a command, path or argument list:
 *  - readProperty accepts only keys on AdvancedAllowList.PROPERTIES;
 *  - runDiagnostic accepts only an AdvancedDiagnostic enum name with a fixed argv.
 */
interface IAdvancedMaintenanceService {
    String identity() = 1;
    String readProperty(String key) = 2;
    String runDiagnostic(String diagnosticId) = 3;
    void destroy() = 16777114;
}
