package ru.corvex.mailav.pipeline;

import java.io.IOException;

/** The scanner returned no trustworthy verdict; no checked event was published. */
final class AvVerdictUnavailableException extends IOException {
    AvVerdictUnavailableException() {
        super("Antivirus verdict unavailable; message retained for retry");
    }
}
