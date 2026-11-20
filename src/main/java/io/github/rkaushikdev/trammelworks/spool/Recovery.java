package io.github.rkaushikdev.trammelworks.spool;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What startup had to do to one interrupted segment.
 *
 * @param path the partial file found
 * @param sealed the segment it became, unless nothing in it was whole
 * @param frames how many good frames it held
 * @param droppedBytes the torn tail cut off
 * @param removed whether the file was removed because nothing in it was whole
 */
public record Recovery(Path path, Optional<SegmentInfo> sealed, int frames, long droppedBytes, boolean removed) {}
