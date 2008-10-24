/*
 * See LICENSE file in distribution for copyright and licensing information.
 */
package org.jvyaml;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jvyaml.events.AliasEvent;
import org.jvyaml.events.DocumentEndEvent;
import org.jvyaml.events.DocumentStartEvent;
import org.jvyaml.events.MappingEndEvent;
import org.jvyaml.events.MappingStartEvent;
import org.jvyaml.events.ScalarEvent;
import org.jvyaml.events.SequenceEndEvent;
import org.jvyaml.events.SequenceStartEvent;
import org.jvyaml.events.StreamEndEvent;
import org.jvyaml.events.StreamStartEvent;
import org.jvyaml.nodes.CollectionNode;
import org.jvyaml.nodes.MappingNode;
import org.jvyaml.nodes.Node;
import org.jvyaml.nodes.ScalarNode;
import org.jvyaml.nodes.SequenceNode;

/**
 * @see PyYAML 3.06 for more information
 */
public class SerializerImpl implements Serializer {
    private Emitter emitter;
    private Resolver resolver;
    private YAMLConfig options;
    private boolean useExplicitStart;
    private boolean useExplicitEnd;
    private int[] useVersion;
    private boolean useTags;
    private String anchorTemplate;
    private Set serializedNodes;
    private Map anchors;
    private int lastAnchorId;
    private boolean closed;
    private boolean opened;

    public SerializerImpl(final Emitter emitter, final Resolver resolver, final YAMLConfig opts) {
        this.emitter = emitter;
        this.resolver = resolver;
        this.options = opts;
        this.useExplicitStart = opts.explicitStart();
        this.useExplicitEnd = opts.explicitEnd();
        int[] version = new int[2];
        if (opts.useVersion()) {
            final String v1 = opts.version();
            final int index = v1.indexOf('.');
            version[0] = Integer.parseInt(v1.substring(0, index));
            version[1] = Integer.parseInt(v1.substring(index + 1));
        } else {
            version = null;
        }
        this.useVersion = version;
        this.useTags = opts.useHeader();
        this.anchorTemplate = opts.anchorFormat() == null ? "id{0,number,####}" : opts
                .anchorFormat();
        this.serializedNodes = new HashSet();
        this.anchors = new HashMap();
        this.lastAnchorId = 0;
        this.closed = false;
        this.opened = false;
    }

    protected boolean ignoreAnchor(final Node node) {
        return false;
    }

    public void open() throws IOException {
        if (!closed && !opened) {
            this.emitter.emit(new StreamStartEvent(null, null));
            this.opened = true;
        } else if (closed) {
            throw new SerializerException("serializer is closed");
        } else {
            throw new SerializerException("serializer is already opened");
        }
    }

    public void close() throws IOException {
        if (!opened) {
            throw new SerializerException("serializer is not opened");
        } else if (!closed) {
            this.emitter.emit(new StreamEndEvent());
            this.closed = true;
            this.opened = false;
        }
    }

    public void serialize(final Node node) throws IOException {
        if (!this.closed && !this.opened) {
            throw new SerializerException("serializer is not opened");
        } else if (this.closed) {
            throw new SerializerException("serializer is closed");
        }
        this.emitter.emit(new DocumentStartEvent(null, null, this.useExplicitStart,
                this.useVersion, null));
        anchorNode(node);
        serializeNode(node, null, null);
        this.emitter.emit(new DocumentEndEvent(null, null, this.useExplicitEnd));
        this.serializedNodes = new HashSet();
        this.anchors = new HashMap();
        this.lastAnchorId = 0;
    }

    private void anchorNode(final Node node) {
        if (!ignoreAnchor(node)) {
            if (this.anchors.containsKey(node)) {
                String anchor = (String) this.anchors.get(node);
                if (null == anchor) {
                    anchor = generateAnchor(node);
                    this.anchors.put(node, anchor);
                }
            } else {
                this.anchors.put(node, null);
                if (node instanceof SequenceNode) {
                    for (final Iterator iter = ((List) node.getValue()).iterator(); iter.hasNext();) {
                        anchorNode((Node) iter.next());
                    }
                } else if (node instanceof MappingNode) {
                    final Map value = (Map) node.getValue();
                    for (final Iterator iter = value.keySet().iterator(); iter.hasNext();) {
                        final Node key = (Node) iter.next();
                        anchorNode(key);
                        anchorNode((Node) value.get(key));
                    }
                }
            }
        }
    }

    private String generateAnchor(final Node node) {
        this.lastAnchorId++;
        return new MessageFormat(this.anchorTemplate).format(new Object[] { new Integer(
                this.lastAnchorId) });
    }

    private void serializeNode(final Node node, final Node parent, final Object index)
            throws IOException {
        final String tAlias = (String) this.anchors.get(node);
        if (this.serializedNodes.contains(node) && tAlias != null) {
            this.emitter.emit(new AliasEvent(tAlias, null, null));
        } else {
            this.serializedNodes.add(node);
            this.resolver.descendResolver(parent, index);
            if (node instanceof ScalarNode) {
                final String detectedTag = this.resolver.resolve(ScalarNode.class, (String) node
                        .getValue(), new boolean[] { true, false });
                final String defaultTag = this.resolver.resolve(ScalarNode.class, (String) node
                        .getValue(), new boolean[] { false, true });
                final boolean[] implicit = new boolean[] { false, false };
                if (!options.explicitTypes()) {
                    implicit[0] = node.getTag().equals(detectedTag);
                    implicit[1] = node.getTag().equals(defaultTag);
                }
                this.emitter.emit(new ScalarEvent(tAlias, node.getTag(), implicit, (String) node
                        .getValue(), null, null, ((ScalarNode) node).getStyle()));
            } else if (node instanceof SequenceNode) {
                final boolean implicit = !options.explicitTypes()
                        && (node.getTag().equals(this.resolver.resolve(SequenceNode.class, null,
                                new boolean[] { true, true })));
                this.emitter.emit(new SequenceStartEvent(tAlias, node.getTag(), implicit, null,
                        null, ((CollectionNode) node).getFlowStyle()));
                int ix = 0;
                for (final Iterator iter = ((List) node.getValue()).iterator(); iter.hasNext();) {
                    serializeNode((Node) iter.next(), node, new Integer(ix++));
                }
                this.emitter.emit(new SequenceEndEvent());
            } else if (node instanceof MappingNode) {
                final boolean implicit = !options.explicitTypes()
                        && (node.getTag().equals(this.resolver.resolve(MappingNode.class, null,
                                new boolean[] { true, true })));
                this.emitter.emit(new MappingStartEvent(tAlias, node.getTag(), implicit, null,
                        null, ((CollectionNode) node).getFlowStyle()));
                final Map value = (Map) node.getValue();
                for (final Iterator iter = value.keySet().iterator(); iter.hasNext();) {
                    final Node key = (Node) iter.next();
                    serializeNode(key, node, null);
                    serializeNode((Node) value.get(key), node, key);
                }
                this.emitter.emit(new MappingEndEvent());
            }
        }
    }
}
