package io.github.rkaushikdev.trammelworks.archive;

/**
 * Whether one record may be deleted, and why not if it may not.
 *
 * @param deletable whether it may go
 * @param reason why or why not
 * @param hold the hold that keeps it, or empty
 */
public record RetentionDecision(boolean deletable, String reason, String hold) {}
