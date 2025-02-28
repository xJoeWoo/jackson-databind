package com.fasterxml.jackson.databind.deser.jackson;

import java.util.Arrays;

import com.fasterxml.jackson.core.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.util.RawValue;

/**
 * Deserializer that can build instances of {@link JsonNode} from any
 * JSON content, using appropriate {@link JsonNode} type.
 *<p>
 * Rewritten in Jackson 2.13 to avoid recursion and allow handling of
 * very deeply nested structures.
 */
public class JsonNodeDeserializer
    extends BaseNodeDeserializer<JsonNode>
{
    /**
     * Singleton instance of generic deserializer for {@link JsonNode}.
     * Only used for types other than JSON Object and Array.
     */
    private final static JsonNodeDeserializer instance = new JsonNodeDeserializer();

    protected JsonNodeDeserializer() {
        // `null` means that explicit "merge" is honored and may or may not work, but
        // that per-type and global defaults do not enable merging. This because
        // some node types (Object, Array) do support, others don't.
        super(JsonNode.class, null);
    }

    /**
     * Factory method for accessing deserializer for specific node type
     */
    public static ValueDeserializer<? extends JsonNode> getDeserializer(Class<?> nodeClass)
    {
        if (nodeClass == ObjectNode.class) {
            return ObjectDeserializer.getInstance();
        }
        if (nodeClass == ArrayNode.class) {
            return ArrayDeserializer.getInstance();
        }
        // For others, generic one works fine
        return instance;
    }

    /*
    /**********************************************************************
    /* Actual deserialization method implementations
    /**********************************************************************
     */

    @Override
    public JsonNode getNullValue(DeserializationContext ctxt) {
        return ctxt.getNodeFactory().nullNode();
    }

    /**
     * Implementation that will produce types of any JSON nodes; not just one
     * deserializer is registered to handle (in case of more specialized handler).
     * Overridden by typed sub-classes for more thorough checking
     */
    @Override
    public JsonNode deserialize(JsonParser p, DeserializationContext ctxt)
        throws JacksonException
    {
        final ContainerStack stack = new ContainerStack();
        final JsonNodeFactory nodeF = ctxt.getNodeFactory();
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_OBJECT) {
            return _deserializeContainerNoRecursion(p, ctxt, nodeF, stack, nodeF.objectNode());
        }
        if (t == JsonToken.START_ARRAY) {
            return _deserializeContainerNoRecursion(p, ctxt, nodeF, stack, nodeF.arrayNode());
        }
        switch (p.currentTokenId()) {
        case JsonTokenId.ID_END_OBJECT:
            return nodeF.objectNode();
        case JsonTokenId.ID_PROPERTY_NAME:
            return _deserializeObjectAtName(p, ctxt, nodeF, stack);
        default:
        }
        return _deserializeAnyScalar(p, ctxt);
    }

    /*
    /**********************************************************************
    /* Specific instances for more accurate types
    /**********************************************************************
     */

    /**
     * Implementation used when declared type is specifically {@link ObjectNode}.
     */
    final static class ObjectDeserializer
        extends BaseNodeDeserializer<ObjectNode>
    {
        protected final static ObjectDeserializer _instance = new ObjectDeserializer();

        protected ObjectDeserializer() { super(ObjectNode.class, true); }

        public static ObjectDeserializer getInstance() { return _instance; }

        @Override
        public ObjectNode deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException
        {
            final JsonNodeFactory nodeF = ctxt.getNodeFactory();
            if (p.isExpectedStartObjectToken()) {
                final ObjectNode root = nodeF.objectNode();
                _deserializeContainerNoRecursion(p, ctxt, nodeF, new ContainerStack(), root);
                return root;
            }
            if (p.hasToken(JsonToken.PROPERTY_NAME)) {
                return _deserializeObjectAtName(p, ctxt, nodeF, new ContainerStack());
            }
            // 23-Sep-2015, tatu: Ugh. We may also be given END_OBJECT (similar to PROPERTY_NAME),
            //    if caller has advanced to the first token of Object, but for empty Object
            if (p.hasToken(JsonToken.END_OBJECT)) {
                return nodeF.objectNode();
            }
            return (ObjectNode) ctxt.handleUnexpectedToken(getValueType(ctxt), p);
         }

        /**
         * Variant needed to support both root-level `updateValue()` and merging.
         */
        @Override
        public ObjectNode deserialize(JsonParser p, DeserializationContext ctxt,
                ObjectNode node) throws JacksonException
        {
            if (p.isExpectedStartObjectToken() || p.hasToken(JsonToken.PROPERTY_NAME)) {
                return (ObjectNode) updateObject(p, ctxt, (ObjectNode) node,
                        new ContainerStack());
            }
            return (ObjectNode) ctxt.handleUnexpectedToken(getValueType(ctxt), p);
        }
    }

    /**
     * Implementation used when declared type is specifically {@link ArrayNode}.
     */
    final static class ArrayDeserializer
        extends BaseNodeDeserializer<ArrayNode>
    {
        protected final static ArrayDeserializer _instance = new ArrayDeserializer();

        protected ArrayDeserializer() { super(ArrayNode.class, true); }

        public static ArrayDeserializer getInstance() { return _instance; }

        @Override
        public ArrayNode deserialize(JsonParser p, DeserializationContext ctxt)
            throws JacksonException
        {
            if (p.isExpectedStartArrayToken()) {
                final JsonNodeFactory nodeF = ctxt.getNodeFactory();
                final ArrayNode arrayNode = nodeF.arrayNode();
                _deserializeContainerNoRecursion(p, ctxt, nodeF,
                        new ContainerStack(), arrayNode);
                return arrayNode;
            }
            return (ArrayNode) ctxt.handleUnexpectedToken(getValueType(ctxt), p);
        }

        /**
         * Variant needed to support both root-level `updateValue()` and merging.
         */
        @Override
        public ArrayNode deserialize(JsonParser p, DeserializationContext ctxt,
                ArrayNode arrayNode) throws JacksonException
        {
            if (p.isExpectedStartArrayToken()) {
                _deserializeContainerNoRecursion(p, ctxt, ctxt.getNodeFactory(),
                        new ContainerStack(), arrayNode);
                return arrayNode;
            }
            return (ArrayNode) ctxt.handleUnexpectedToken(getValueType(ctxt), p);
        }
    }
}

/**
 * Base class for all actual {@link JsonNode} deserializer implementations.
 *<p>
 * Starting with Jackson 2.13 uses iteration instead of recursion: this allows
 * handling of very deeply nested input structures.
 */
abstract class BaseNodeDeserializer<T extends JsonNode>
    extends StdDeserializer<T>
{
    protected final Boolean _supportsUpdates;

    public BaseNodeDeserializer(Class<T> vc, Boolean supportsUpdates) {
        super(vc);
        _supportsUpdates = supportsUpdates;
    }

    @Override
    public Object deserializeWithType(JsonParser p, DeserializationContext ctxt,
            TypeDeserializer typeDeserializer)
        throws JacksonException
    {
        // Output can be as JSON Object, Array or scalar: no way to know a priori:
        return typeDeserializer.deserializeTypedFromAny(p, ctxt);
    }

    @Override // since 2.12
    public LogicalType logicalType() {
        return LogicalType.Untyped;
    }

    // 07-Nov-2014, tatu: When investigating [databind#604], realized that it makes
    //   sense to also mark this is cachable, since lookup not exactly free, and
    //   since it's not uncommon to "read anything"
    @Override
    public boolean isCachable() { return true; }

    @Override
    public Boolean supportsUpdate(DeserializationConfig config) {
        return _supportsUpdates;
    }

    /*
    /**********************************************************************
    /* Duplicate handling
    /**********************************************************************
     */

    /**
     * Method called when there is a duplicate value for an Object property.
     * By default we don't care, and the last value is used.
     * Can be overridden to provide alternate handling, such as throwing
     * an exception, or choosing different strategy for combining values
     * or choosing which one to keep.
     *
     * @param propName Name of the property for which duplicate value was found
     * @param objectNode Object node that contains values
     * @param oldValue Value that existed for the object node before newValue
     *   was added
     * @param newValue Newly added value just added to the object node
     */
    protected void _handleDuplicateProperty(JsonParser p, DeserializationContext ctxt,
            JsonNodeFactory nodeFactory,
            String propName, ObjectNode objectNode,
            JsonNode oldValue, JsonNode newValue)
        throws JacksonException
    {
        // [databind#237]: Report an error if asked to do so:
        if (ctxt.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)) {
            // 11-Sep-2019, tatu: Can not pass "property name" because we may be
            //    missing enclosing JSON content context...
// ctxt.reportPropertyInputMismatch(JsonNode.class, propName,
            ctxt.reportInputMismatch(JsonNode.class,
"Duplicate property \"%s\" for `ObjectNode`: not allowed when `DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY` enabled",
                    propName);
        }
        // [databind#2732]: Special case for XML; automatically coerce into `ArrayNode`
        if (ctxt.isEnabled(StreamReadCapability.DUPLICATE_PROPERTIES)) {
            // Note that ideally we wouldn't have to shuffle things but... Map.putIfAbsent()
            // only added in JDK 8, to efficiently check for add. So...
            if (oldValue.isArray()) { // already was array, to append
                ((ArrayNode) oldValue).add(newValue);
                objectNode.replace(propName, oldValue);
            } else { // was not array, convert
                ArrayNode arr = nodeFactory.arrayNode();
                arr.add(oldValue);
                arr.add(newValue);
                objectNode.replace(propName, arr);
            }
        }
    }

    /*
    /**********************************************************************
    /* Helper methods, deserialization
    /**********************************************************************
     */

    /**
     * Alternate deserialization method used when parser already points to first
     * PROPERTY_NAME and not START_OBJECT.
     */
    protected final ObjectNode _deserializeObjectAtName(JsonParser p, DeserializationContext ctxt,
            final JsonNodeFactory nodeFactory, final ContainerStack stack)
        throws JacksonException
    {
        final ObjectNode node = nodeFactory.objectNode();
        String key = p.currentName();
        for (; key != null; key = p.nextName()) {
            JsonNode value;
            JsonToken t = p.nextToken();
            if (t == null) { // can this ever occur?
                t = JsonToken.NOT_AVAILABLE; // can this ever occur?
            }
            switch (t.id()) {
            case JsonTokenId.ID_START_OBJECT:
                value = _deserializeContainerNoRecursion(p, ctxt, nodeFactory,
                        stack, nodeFactory.objectNode());
                break;
            case JsonTokenId.ID_START_ARRAY:
                value = _deserializeContainerNoRecursion(p, ctxt, nodeFactory,
                        stack, nodeFactory.arrayNode());
                break;
            default:
                value = _deserializeAnyScalar(p, ctxt);
            }
            JsonNode old = node.replace(key, value);
            if (old != null) {
                _handleDuplicateProperty(p, ctxt, nodeFactory,
                        key, node, old, value);
            }
        }
        return node;
    }
    
    /**
     * Alternate deserialization method that is to update existing {@link ObjectNode}
     * if possible.
     */
    protected final JsonNode updateObject(JsonParser p, DeserializationContext ctxt,
            final ObjectNode node, final ContainerStack stack)
        throws JacksonException
    {
        String key;
        if (p.isExpectedStartObjectToken()) {
            key = p.nextName();
        } else {
            if (!p.hasToken(JsonToken.PROPERTY_NAME)) {
                return deserialize(p, ctxt);
            }
            key = p.currentName();
        }
        final JsonNodeFactory nodeFactory = ctxt.getNodeFactory();
        for (; key != null; key = p.nextName()) {
            // If not, fall through to regular handling
            JsonToken t = p.nextToken();

            // First: see if we can merge things:
            JsonNode old = node.get(key);
            if (old != null) {
                if (old instanceof ObjectNode) {
                    // [databind#3056]: merging only if had Object and
                    // getting an Object
                    if (t == JsonToken.START_OBJECT) {
                        JsonNode newValue = updateObject(p, ctxt, (ObjectNode) old, stack);
                        if (newValue != old) {
                            node.set(key, newValue);
                        }
                        continue;
                    }
                } else if (old instanceof ArrayNode) {
                    // [databind#3056]: related to Object handling, ensure
                    // Array values also match for mergeability
                    if (t == JsonToken.START_ARRAY) {
                        // 28-Mar-2021, tatu: We'll only append entries so not very different
                        //    from "regular" deserializeArray...
                        _deserializeContainerNoRecursion(p, ctxt, nodeFactory,
                                stack, (ArrayNode) old);
                        continue;
                    }
                }
            }
            if (t == null) { // can this ever occur?
                t = JsonToken.NOT_AVAILABLE;
            }
            JsonNode value;
            switch (t.id()) {
            case JsonTokenId.ID_START_OBJECT:
                value = _deserializeContainerNoRecursion(p, ctxt, nodeFactory,
                        stack, nodeFactory.objectNode());
                break;
            case JsonTokenId.ID_START_ARRAY:
                value = _deserializeContainerNoRecursion(p, ctxt, nodeFactory,
                        stack, nodeFactory.arrayNode());
                break;
            case JsonTokenId.ID_STRING:
                value = nodeFactory.textNode(p.getText());
                break;
            case JsonTokenId.ID_NUMBER_INT:
                value = _fromInt(p, ctxt, nodeFactory);
                break;
            case JsonTokenId.ID_TRUE:
                value = nodeFactory.booleanNode(true);
                break;
            case JsonTokenId.ID_FALSE:
                value = nodeFactory.booleanNode(false);
                break;
            case JsonTokenId.ID_NULL:
                value = nodeFactory.nullNode();
                break;
            default:
                value = _deserializeRareScalar(p, ctxt);
            }
            // 15-Feb-2021, tatu: I don't think this should have been called
            //   on update case (was until 2.12.2) and was simply result of
            //   copy-paste.
            /*
            if (old != null) {
                _handleDuplicateProperty(p, ctxt, nodeFactory,
                        key, node, old, value);
            }
            */
            node.set(key, value);
        }
        return node;
    }

    // Non-recursive alternative
    protected final ContainerNode<?> _deserializeContainerNoRecursion(JsonParser p, DeserializationContext ctxt,
            JsonNodeFactory nodeFactory, ContainerStack stack, final ContainerNode<?> root)
        throws JacksonException
    {
        ContainerNode<?> curr = root;
        final int intCoercionFeats = ctxt.getDeserializationFeatures() & F_MASK_INT_COERCIONS;

        outer_loop:
        do {
            if (curr instanceof ObjectNode) {
                ObjectNode currObject = (ObjectNode) curr;
                String propName = p.nextName();

                objectLoop:
                for (; propName != null; propName = p.nextName()) {
                    JsonNode value;
                    JsonToken t = p.nextToken();
                    if (t == null) { // unexpected end-of-input (or bad buffering?)
                        t = JsonToken.NOT_AVAILABLE; // to trigger an exception
                    }
                    switch (t.id()) {
                    case JsonTokenId.ID_START_OBJECT:
                        {
                            ObjectNode newOb = nodeFactory.objectNode();
                            JsonNode old = currObject.replace(propName, newOb);
                            if (old != null) {
                                _handleDuplicateProperty(p, ctxt, nodeFactory,
                                        propName, currObject, old, newOb);
                            }
                            stack.push(curr);
                            curr = currObject = newOb;
                            // We can actually take a short-cut with nested Objects...
                            continue objectLoop;
                        }
                    case JsonTokenId.ID_START_ARRAY:
                        {
                            ArrayNode newOb = nodeFactory.arrayNode();
                            JsonNode old = currObject.replace(propName, newOb);
                            if (old != null) {
                                _handleDuplicateProperty(p, ctxt, nodeFactory,
                                        propName, currObject, old, newOb);
                            }
                            stack.push(curr);
                            curr = newOb;
                        }
                        continue outer_loop;
                    case JsonTokenId.ID_STRING:
                        value = nodeFactory.textNode(p.getText());
                        break;
                    case JsonTokenId.ID_NUMBER_INT:
                        value = _fromInt(p, intCoercionFeats, nodeFactory);
                        break;
                    case JsonTokenId.ID_NUMBER_FLOAT:
                        value = _fromFloat(p, ctxt, nodeFactory);
                        break;
                    case JsonTokenId.ID_TRUE:
                        value = nodeFactory.booleanNode(true);
                        break;
                    case JsonTokenId.ID_FALSE:
                        value = nodeFactory.booleanNode(false);
                        break;
                    case JsonTokenId.ID_NULL:
                        value = nodeFactory.nullNode();
                        break;
                    default:
                        value = _deserializeRareScalar(p, ctxt);
                    }
                    JsonNode old = currObject.replace(propName, value);
                    if (old != null) {
                        _handleDuplicateProperty(p, ctxt, nodeFactory,
                                propName, currObject, old, value);
                    }
                }
                // reached not-property-name, should be END_OBJECT (verify?)
            } else {
                // Otherwise we must have an array
                final ArrayNode currArray = (ArrayNode) curr;

                arrayLoop:
                while (true) {
                    JsonToken t = p.nextToken();
                    if (t == null) { // unexpected end-of-input (or bad buffering?)
                        t = JsonToken.NOT_AVAILABLE; // to trigger an exception
                    }
                    switch (t.id()) {
                    case JsonTokenId.ID_START_OBJECT:
                        stack.push(curr);
                        curr = nodeFactory.objectNode();
                        currArray.add(curr);
                        continue outer_loop;
                    case JsonTokenId.ID_START_ARRAY:
                        stack.push(curr);
                        curr = nodeFactory.arrayNode();
                        currArray.add(curr);
                        continue outer_loop;
                    case JsonTokenId.ID_END_ARRAY:
                        break arrayLoop;
                    case JsonTokenId.ID_STRING:
                        currArray.add(nodeFactory.textNode(p.getText()));
                        continue arrayLoop;
                    case JsonTokenId.ID_NUMBER_INT:
                        currArray.add(_fromInt(p, intCoercionFeats, nodeFactory));
                        continue arrayLoop;
                    case JsonTokenId.ID_NUMBER_FLOAT:
                        currArray.add(_fromFloat(p, ctxt, nodeFactory));
                        continue arrayLoop;
                    case JsonTokenId.ID_TRUE:
                        currArray.add(nodeFactory.booleanNode(true));
                        continue arrayLoop;
                    case JsonTokenId.ID_FALSE:
                        currArray.add(nodeFactory.booleanNode(false));
                        continue arrayLoop;
                    case JsonTokenId.ID_NULL:
                        currArray.add(nodeFactory.nullNode());
                        continue arrayLoop;
                    default:
                        currArray.add(_deserializeRareScalar(p, ctxt));
                        continue arrayLoop;
                    }
                }
                // Reached end of array (or input), so...
            }

            // Either way, Object or Array ended, return up nesting level:
            curr = stack.popOrNull();
        } while (curr != null);
        return root;
    }

    // Was called "deserializeAny()" in 2.12 and prior
    protected final JsonNode _deserializeAnyScalar(JsonParser p, DeserializationContext ctxt)
        throws JacksonException
    {
        final JsonNodeFactory nodeF = ctxt.getNodeFactory();
        switch (p.currentTokenId()) {
        case JsonTokenId.ID_END_OBJECT:
            return nodeF.objectNode();
        case JsonTokenId.ID_STRING:
            return nodeF.textNode(p.getText());
        case JsonTokenId.ID_NUMBER_INT:
            return _fromInt(p, ctxt, nodeF);
        case JsonTokenId.ID_NUMBER_FLOAT:
            return _fromFloat(p, ctxt, nodeF);
        case JsonTokenId.ID_TRUE:
            return nodeF.booleanNode(true);
        case JsonTokenId.ID_FALSE:
            return nodeF.booleanNode(false);
        case JsonTokenId.ID_NULL:
            return nodeF.nullNode();
        case JsonTokenId.ID_EMBEDDED_OBJECT:
            return _fromEmbedded(p, ctxt);

        // Caller should check for anything else
        default:
        }
        return (JsonNode) ctxt.handleUnexpectedToken(handledType(), p);
    }

    protected final JsonNode _deserializeRareScalar(JsonParser p, DeserializationContext ctxt)
        throws JacksonException
    {
        // 28-Mar-2021, tatu: Only things that caller does not check
        switch (p.currentTokenId()) {
        case JsonTokenId.ID_END_OBJECT: // for empty JSON Objects we may point to this?
            return ctxt.getNodeFactory().objectNode();
        case JsonTokenId.ID_NUMBER_FLOAT:
            return _fromFloat(p, ctxt, ctxt.getNodeFactory());
        case JsonTokenId.ID_EMBEDDED_OBJECT:
            return _fromEmbedded(p, ctxt);

        // Caller should check for anything else
        default:
        }
        return (JsonNode) ctxt.handleUnexpectedToken(getValueType(ctxt), p);
    }

    protected final JsonNode _fromInt(JsonParser p, int coercionFeatures,
            JsonNodeFactory nodeFactory)
        throws JacksonException
    {
        if (coercionFeatures != 0) {
            if (DeserializationFeature.USE_BIG_INTEGER_FOR_INTS.enabledIn(coercionFeatures)) {
                return nodeFactory.numberNode(p.getBigIntegerValue());
            }
            return nodeFactory.numberNode(p.getLongValue());
        }
        final JsonParser.NumberType nt = p.getNumberType();
        if (nt == JsonParser.NumberType.INT) {
            return nodeFactory.numberNode(p.getIntValue());
        }
        if (nt == JsonParser.NumberType.LONG) {
            return nodeFactory.numberNode(p.getLongValue());
        }
        return nodeFactory.numberNode(p.getBigIntegerValue());
    }

    protected final JsonNode _fromInt(JsonParser p, DeserializationContext ctxt,
            JsonNodeFactory nodeFactory)
        throws JacksonException
    {
        JsonParser.NumberType nt;
        int feats = ctxt.getDeserializationFeatures();
        if ((feats & F_MASK_INT_COERCIONS) != 0) {
            if (DeserializationFeature.USE_BIG_INTEGER_FOR_INTS.enabledIn(feats)) {
                nt = JsonParser.NumberType.BIG_INTEGER;
            } else if (DeserializationFeature.USE_LONG_FOR_INTS.enabledIn(feats)) {
                nt = JsonParser.NumberType.LONG;
            } else {
                nt = p.getNumberType();
            }
        } else {
            nt = p.getNumberType();
        }
        if (nt == JsonParser.NumberType.INT) {
            return nodeFactory.numberNode(p.getIntValue());
        }
        if (nt == JsonParser.NumberType.LONG) {
            return nodeFactory.numberNode(p.getLongValue());
        }
        return nodeFactory.numberNode(p.getBigIntegerValue());
    }

    protected final JsonNode _fromFloat(JsonParser p, DeserializationContext ctxt,
            final JsonNodeFactory nodeFactory)
        throws JacksonException
    {
        JsonParser.NumberType nt = p.getNumberType();
        if (nt == JsonParser.NumberType.BIG_DECIMAL) {
            return nodeFactory.numberNode(p.getDecimalValue());
        }
        if (ctxt.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)) {
            // 20-May-2016, tatu: As per [databind#1028], need to be careful
            //   (note: JDK 1.8 would have `Double.isFinite()`)
            if (p.isNaN()) {
                return nodeFactory.numberNode(p.getDoubleValue());
            }
            return nodeFactory.numberNode(p.getDecimalValue());
        }
        if (nt == JsonParser.NumberType.FLOAT) {
            return nodeFactory.numberNode(p.getFloatValue());
        }
        return nodeFactory.numberNode(p.getDoubleValue());
    }

    protected final JsonNode _fromEmbedded(JsonParser p, DeserializationContext ctxt)
        throws JacksonException
    {
        final JsonNodeFactory nodeF = ctxt.getNodeFactory();
        final Object ob = p.getEmbeddedObject();

        if (ob == null) { // should this occur?
            return nodeF.nullNode();
        }
        Class<?> type = ob.getClass();
        if (type == byte[].class) { // most common special case
            return nodeF.binaryNode((byte[]) ob);
        }
        // [databind#743]: Don't forget RawValue
        if (ob instanceof RawValue) {
            return nodeF.rawValueNode((RawValue) ob);
        }
        if (ob instanceof JsonNode) {
            // [databind#433]: but could also be a JsonNode hiding in there!
            return (JsonNode) ob;
        }
        // any other special handling needed?
        return nodeF.pojoNode(ob);
    }

    /*
    /**********************************************************************
    /* Helper classes
    /**********************************************************************
     */

    /**
     * Optimized variant similar in functionality to (a subset of)
     * {@link java.util.ArrayDeque}; used to hold enclosing Array/Object
     * nodes during recursion-as-iteration.
     */
    @SuppressWarnings("rawtypes")
    final static class ContainerStack
    {
        private ContainerNode[] _stack;
        private int _top, _end;

        public ContainerStack() { }

        // Not used yet but useful for limits (fail at [some high depth])
        public int size() { return _top; }

        public void push(ContainerNode node)
        {
            if (_top < _end) {
                _stack[_top++] = node;
                return;
            }
            if (_stack == null) {
                _end = 10;
                _stack = new ContainerNode[_end];
            } else {
                // grow by 50%, for most part
                _end += Math.min(4000, Math.max(20, _end>>1));
                _stack = Arrays.copyOf(_stack, _end);
            }
            _stack[_top++] = node;
        }

        public ContainerNode popOrNull() {
            if (_top == 0) {
                return null;
            }
            // note: could clean up stack but due to usage pattern, should not make
            // any difference -- all nodes joined during and after construction and
            // after construction the whole stack is discarded
            return _stack[--_top];
        }
    }
}
