package ru.corvex.mailstorage;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * ВРЕМЕННЫЙ диагностический слушатель: печатает провалы тестов как
 * workflow-команду `::error::`, чтобы имя теста было видно в аннотациях
 * check-run (логи job из окружения недоступны). Удаляется после починки.
 */
public class DiagFailureListener implements TestExecutionListener {

    @Override
    public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        if (result.getStatus() != TestExecutionResult.Status.FAILED) {
            return;
        }
        String message = result.getThrowable().map(DiagFailureListener::describe).orElse("no throwable");
        System.out.println("::error::FAILED " + test.getDisplayName() + " :: " + message);
    }

    private static String describe(Throwable t) {
        StringBuilder text = new StringBuilder(t.getClass().getName() + ": " + t.getMessage());
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth++ < 3) {
            text.append(" <- ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith("ru.corvex")) {
                text.append(" @ ").append(frame);
                break;
            }
        }
        return text.toString().replace('\r', ' ').replace('\n', ' ');
    }
}
