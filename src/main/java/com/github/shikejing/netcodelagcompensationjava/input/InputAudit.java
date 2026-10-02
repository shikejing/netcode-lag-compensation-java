package com.github.shikejing.netcodelagcompensationjava.input;

import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;

/**
 * One traceable ingestion decision: every input (accepted or not) is audited,
 * so the difference before/after an out-of-order correction is explainable.
 */
public record InputAudit(
        long serverTimeMs,
        long currentTick,
        InputCommand command,
        long targetTick,
        InputDisposition disposition,
        String detail
) {
}
