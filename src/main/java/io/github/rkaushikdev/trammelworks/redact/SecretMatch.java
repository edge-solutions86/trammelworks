package io.github.rkaushikdev.trammelworks.redact;

/**
 * Something that must not leave the site, and where it was.
 *
 * @param start where it starts in the line
 * @param end just past its end
 * @param kind what it is
 * @param text the matched text
 */
public record SecretMatch(int start, int end, SecretKind kind, String text) {}
