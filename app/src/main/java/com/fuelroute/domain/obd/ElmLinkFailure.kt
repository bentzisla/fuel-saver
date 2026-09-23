package com.fuelroute.domain.obd

/**
 * Why an ELM exchange produced no reply. Distinguishes a genuinely slow/silent adapter from a
 * dead or half-open RFCOMM link, which used to be reported identically as `INIT TIMEOUT`.
 */
enum class ElmLinkFailure {
    /** The deadline expired with no `>` prompt; the link was closed to unblock the read. */
    TIMEOUT,

    /** Writing the command failed: the socket was already broken. */
    WRITE_FAILED,

    /** The read hit end-of-stream: the dongle (or the stack) hung up the RFCOMM channel. */
    EOF,

    /** The read threw an IOException that was not our own close (e.g. "socket closed", -1). */
    READ_ERROR,

    /** The link was already closed (by a previous failure, a stop, or a cancelled exchange). */
    LINK_CLOSED,
}
