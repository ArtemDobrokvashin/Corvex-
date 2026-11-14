package ru.corvex.mailav.pipeline;

import java.io.IOException;

/** ClamAV reported an incomplete scan. Do not treat it as an infection verdict or blindly replay. */
final class AvScanLimitExceededException extends IOException {
    AvScanLimitExceededException() {
        super("Antivirus scan limit exceeded; message retained for reconciliation");
    }
}
