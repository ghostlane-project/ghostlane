package org.olcbox.app.net.core.index

import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.lines.Hysteria2
import org.olcbox.app.net.core.lines.Reality
import org.olcbox.app.net.core.lines.WebTunnel

/**
 * The kinds under `net/core/lines`, by name. Written by hand for the move of
 * the builder; the registries replace it with an index made from the files of
 * that directory, so that a new kind is a new file and no edit here.
 */
internal val coreLineIndex: List<CoreLine> = listOf(Hysteria2, Reality, WebTunnel)
