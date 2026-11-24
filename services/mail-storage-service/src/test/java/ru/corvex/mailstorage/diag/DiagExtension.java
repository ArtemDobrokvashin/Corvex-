package ru.corvex.mailstorage.diag;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/** TEMP: печатает имя упавшего теста workflow-командой — она попадёт в аннотации check-run. */
public class DiagExtension implements TestExecutionExceptionHandler {

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        String where = context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredDisplayName();
        String message = String.valueOf(throwable).replace('\n', ' ').replace('\r', ' ');
        if (message.length() > 300) {
            message = message.substring(0, 300);
        }
        System.out.println("::error::DIAGFAIL " + where + " :: " + message);
        System.out.flush();
        throw throwable;
    }
}
