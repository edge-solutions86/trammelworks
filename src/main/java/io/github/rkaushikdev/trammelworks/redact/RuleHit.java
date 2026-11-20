package io.github.rkaushikdev.trammelworks.redact;

/**
 * Where a site rule fired.
 *
 * @param rule the rule's name
 * @param start where the removed text started
 * @param end just past its end
 * @param text what was removed
 */
public record RuleHit(String rule, int start, int end, String text) {}
