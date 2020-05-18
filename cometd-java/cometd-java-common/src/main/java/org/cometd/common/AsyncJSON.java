/*
 * Copyright (c) 2008-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.common;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ajax.JSON;

public class AsyncJSON<T> implements Closeable {
    public static class Factory {
        private Trie<String> cache;
        private Map<String, JSON.Convertor> convertors;
        private boolean debug;

        public boolean isDebug() {
            return debug;
        }

        public void setDebug(boolean debug) {
            this.debug = debug;
        }

        public boolean cache(String value) {
            if (cache == null) {
                cache = new ArrayTernaryTrie.Growing<>(false, 64, 64);
            }
            return cache.put("\"" + value + "\"", value);
        }

        public String cached(ByteBuffer buffer) {
            if (cache != null) {
                String result = cache.getBest(buffer, 0, buffer.remaining());
                if (result != null) {
                    buffer.position(buffer.position() + result.length() + 2);
                    return result;
                }
            }
            return null;
        }

        public <T> AsyncJSON<T> newAsyncJSON(Consumer<T> onComplete) {
            return new AsyncJSON<>(this, onComplete);
        }

        public void addConvertor(String className, JSON.Convertor convertor) {
            if (convertors == null) {
                convertors = new ConcurrentHashMap<>();
            }
            convertors.put(className, convertor);
        }
    }

    private static final Object UNSET = new Object();

    private final Factory factory;
    private final Consumer<T> onComplete;
    private final Utf8StringBuilder stringBuilder;
    private final NumberBuilder numberBuilder;
    private Frame frame;
    private List<ByteBuffer> chunks;

    public AsyncJSON(Factory factory, Consumer<T> onComplete) {
        this.factory = factory;
        this.onComplete = onComplete;
        this.stringBuilder = new Utf8StringBuilder(256);
        this.numberBuilder = new NumberBuilder();
    }

    // Used by tests only.
    int depth() {
        return depth(frame);
    }

    private static int depth(Frame frame) {
        int result = 0;
        while (frame != null && frame.state != State.COMPLETE) {
            ++result;
            frame = frame.parent();
        }
        return result;
    }

    public boolean parse(byte[] bytes) {
        return parse(ByteBuffer.wrap(bytes));
    }

    public boolean parse(ByteBuffer buffer) {
        try {
            if (factory.isDebug()) {
                if (chunks == null) {
                    chunks = new ArrayList<>();
                }
                ByteBuffer copy = buffer.isDirect()
                        ? ByteBuffer.allocateDirect(buffer.remaining())
                        : ByteBuffer.allocate(buffer.remaining());
                copy.put(buffer).flip();
                chunks.add(copy);
                buffer.flip();
            }

            if (frame == null) {
                frame = new Frame(null, State.COMPLETE, UNSET);
            }

            while (true) {
                State state = frame.state;
                switch (state) {
                    case COMPLETE: {
                        if (frame.value == UNSET) {
                            if (parseAny(buffer)) {
                                break;
                            }
                            return false;
                        } else {
                            complete();
                            return true; // TODO: EOF
                        }
                    }
                    case NULL: {
                        if (parseNull(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case TRUE: {
                        if (parseTrue(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case FALSE: {
                        if (parseFalse(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case NUMBER: {
                        if (parseNumber(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case STRING: {
                        if (parseString(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case ESCAPE:
                        if (parseEscape(buffer)) {
                            break;
                        }
                        return false;
                    case UNICODE:
                        if (parseUnicode(buffer)) {
                            break;
                        }
                        return false;
                    case ARRAY: {
                        if (parseArray(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case OBJECT: {
                        if (parseObject(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case OBJECT_FIELD: {
                        if (parseObjectField(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case OBJECT_FIELD_NAME: {
                        if (parseObjectFieldName(buffer)) {
                            break;
                        }
                        return false;
                    }
                    case OBJECT_FIELD_VALUE: {
                        if (parseObjectFieldValue(buffer)) {
                            break;
                        }
                        return false;
                    }
                    default: {
                        throw new IllegalStateException("Invalid state " + state);
                    }
                }
            }
        } catch (Throwable x) {
            reset();
            throw x;
        }
    }

    @Override
    public void close() {
        if (frame == null) {
            return;
        }

        while (true) {
            switch (frame.state) {
                case NUMBER: {
                    Number value = numberBuilder.value();
                    frame = frame.parent();
                    frame.value(value);
                    break;
                }
                case COMPLETE: {
                    if (frame.value != UNSET) {
                        complete();
                    }
                    return;
                }
                default: {
                    return;
                }
            }
        }
    }

    protected HashMap<String, Object> newObject(Context context) {
        return new HashMap<>();
    }

    protected List<T> newArray(Context context) {
        return new ArrayList<>();
    }

    private void complete() {
        @SuppressWarnings("unchecked")
        T result = (T)frame.value;
        reset();
        onComplete.accept(result);
    }

    private void reset() {
        frame = null;
        chunks = null;
    }

    private boolean parseAny(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte peek = buffer.get(buffer.position());
            switch (peek) {
                case '[': {
                    if (parseArray(buffer)) {
                        return true;
                    }
                    break;
                }
                case '{':
                    if (parseObject(buffer)) {
                        return true;
                    }
                    break;
                case '-':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    if (parseNumber(buffer)) {
                        return true;
                    }
                    break;
                case '"': {
                    if (parseString(buffer)) {
                        return true;
                    }
                    break;
                }
                case 'f':
                    if (parseFalse(buffer)) {
                        return true;
                    }
                    break;
                case 'n':
                    if (parseNull(buffer)) {
                        return true;
                    }
                    break;
                case 't':
                    if (parseTrue(buffer)) {
                        return true;
                    }
                    break;
                default:
                    if (Character.isWhitespace(peek)) {
                        buffer.get();
                        break;
                    }
                    throw newInvalidJSON(buffer, "unrecognized JSON value");
            }
        }
        return false;
    }

    private boolean parseNull(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            switch (currentByte) {
                case 'n':
                    if (frame.state != State.NULL) {
                        frame = new Frame(frame, State.NULL, 0);
                        parseNullCharacter(buffer, 0);
                        break;
                    } else {
                        throw newInvalidJSON(buffer, "invalid 'null' literal");
                    }
                case 'u':
                    parseNullCharacter(buffer, 1);
                    break;
                case 'l':
                    int index = (Integer)frame.value;
                    if (index == 2 || index == 3) {
                        parseNullCharacter(buffer, index);
                    } else {
                        throw newInvalidJSON(buffer, "invalid 'null' literal");
                    }
                    if (index == 3) {
                        frame = frame.parent();
                        frame.value(null);
                        return true;
                    }
                    break;
                default:
                    throw newInvalidJSON(buffer, "invalid 'null' literal");
            }
        }
        return false;
    }

    private void parseNullCharacter(ByteBuffer buffer, int index) {
        int value = (Integer)frame.value;
        if (value == index) {
            frame.value = ++value;
        } else {
            throw newInvalidJSON(buffer, "invalid 'null' literal");
        }
    }

    private boolean parseTrue(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            switch (currentByte) {
                case 't':
                    if (frame.state != State.TRUE) {
                        frame = new Frame(frame, State.TRUE, 0);
                        parseTrueCharacter(buffer, 0);
                        break;
                    } else {
                        throw newInvalidJSON(buffer, "invalid 'true' literal");
                    }
                case 'r':
                    parseTrueCharacter(buffer, 1);
                    break;
                case 'u':
                    parseTrueCharacter(buffer, 2);
                    break;
                case 'e':
                    parseTrueCharacter(buffer, 3);
                    frame = frame.parent();
                    frame.value(Boolean.TRUE);
                    return true;
                default:
                    throw newInvalidJSON(buffer, "invalid 'true' literal");
            }
        }
        return false;
    }

    private void parseTrueCharacter(ByteBuffer buffer, int index) {
        int value = (Integer)frame.value;
        if (value == index) {
            frame.value = ++value;
        } else {
            throw newInvalidJSON(buffer, "invalid 'true' literal");
        }
    }

    private boolean parseFalse(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            switch (currentByte) {
                case 'f':
                    if (frame.state != State.FALSE) {
                        frame = new Frame(frame, State.FALSE, 0);
                        parseFalseCharacter(buffer, 0);
                        break;
                    } else {
                        throw newInvalidJSON(buffer, "invalid 'false' literal");
                    }
                case 'a':
                    parseFalseCharacter(buffer, 1);
                    break;
                case 'l':
                    parseFalseCharacter(buffer, 2);
                    break;
                case 's':
                    parseFalseCharacter(buffer, 3);
                    break;
                case 'e':
                    parseFalseCharacter(buffer, 4);
                    frame = frame.parent();
                    frame.value(Boolean.FALSE);
                    return true;
                default:
                    throw newInvalidJSON(buffer, "invalid 'false' literal");
            }
        }
        return false;
    }

    private void parseFalseCharacter(ByteBuffer buffer, int index) {
        int value = (Integer)frame.value;
        if (value == index) {
            frame.value = ++value;
        } else {
            throw newInvalidJSON(buffer, "invalid 'false' literal");
        }
    }

    private boolean parseNumber(ByteBuffer buffer) {
        if (frame.state != State.NUMBER) {
            frame = new Frame(frame, State.NUMBER, numberBuilder);
        }
        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            switch (currentByte) {
                case '+':
                case '-': {
                    if (numberBuilder.appendSign(currentByte)) {
                        break;
                    }
                    throw newInvalidJSON(buffer, "invalid number");
                }
                case '.':
                case 'E':
                case 'e': {
                    if (numberBuilder.appendAlpha(currentByte)) {
                        break;
                    }
                    throw newInvalidJSON(buffer, "invalid number");
                }
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9': {
                    numberBuilder.appendDigit(currentByte);
                    break;
                }
                default: {
                    buffer.position(buffer.position() - 1);
                    Number value = numberBuilder.value();
                    frame = frame.parent();
                    frame.value(value);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean parseString(ByteBuffer buffer) {
        if (buffer.hasRemaining() && frame.state != State.STRING) {
            String result = factory.cached(buffer);
            if (result != null) {
                frame.value(result);
                return true;
            }
        }

        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            switch (currentByte) {
                // Explicit delimiter, handle push and pop in this method.
                case '"': {
                    if (frame.state != State.STRING) {
                        frame = new Frame(frame, State.STRING, stringBuilder);
                        break;
                    } else {
                        String string = stringBuilder.toString();
                        stringBuilder.reset();
                        frame = frame.parent();
                        frame.value(string);
                        return true;
                    }
                }
                case '\\':
                    buffer.position(buffer.position() - 1);
                    if (parseEscape(buffer)) {
                        break;
                    }
                    return false;
                default:
                    stringBuilder.append(currentByte);
                    break;
            }
        }
        return false;
    }

    private boolean parseEscape(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            switch (currentByte) {
                case '\\': {
                    if (frame.state != State.ESCAPE) {
                        frame = new Frame(frame, State.ESCAPE, stringBuilder);
                        break;
                    } else {
                        return parseEscapeCharacter((char)currentByte);
                    }
                }
                case '"':
                case '/':
                    return parseEscapeCharacter((char)currentByte);
                case 'b':
                    return parseEscapeCharacter('\b');
                case 'f':
                    return parseEscapeCharacter('\f');
                case 'n':
                    return parseEscapeCharacter('\n');
                case 'r':
                    return parseEscapeCharacter('\r');
                case 't':
                    return parseEscapeCharacter('\t');
                case 'u': {
                    frame = new Frame(frame, State.UNICODE, ByteBuffer.allocate(4));
                    return parseUnicode(buffer);
                }
                default:
                    throw newInvalidJSON(buffer, "invalid escape sequence");
            }
        }
        return false;
    }

    private boolean parseEscapeCharacter(char escape) {
        frame = frame.parent();
        stringBuilder.append(escape);
        return true;
    }

    private boolean parseUnicode(ByteBuffer buffer) {
        // Expect 4 hex digits.
        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            ByteBuffer hex = (ByteBuffer)frame.value;
            hex.put(hexToByte(buffer, currentByte));
            if (!hex.hasRemaining()) {
                int result = (hex.get(0) << 12) +
                        (hex.get(1) << 8) +
                        (hex.get(2) << 4) +
                        (hex.get(3));
                frame = frame.parent();
                // Also done with escape parsing.
                frame = frame.parent();
                stringBuilder.append((char)result);
                return true;
            }
        }
        return false;
    }

    private byte hexToByte(ByteBuffer buffer, byte currentByte) {
        try {
            return TypeUtil.convertHexDigit(currentByte);
        } catch (Throwable x) {
            throw newInvalidJSON(buffer, "invalid hex digit");
        }
    }

    private boolean parseArray(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte peek = buffer.get(buffer.position());
            switch (peek) {
                // Explicit delimiters, handle push and pop in this method.
                case '[': {
                    buffer.get();
                    frame = new Frame(frame, State.ARRAY, newArray(frame));
                    break;
                }
                case ']': {
                    buffer.get();
                    Object array = frame.value;
                    frame = frame.parent();
                    frame.value(array);
                    return true;
                }
                case ',': {
                    buffer.get();
                    break;
                }
                default: {
                    if (Character.isWhitespace(peek)) {
                        buffer.get();
                        break;
                    } else {
                        if (parseAny(buffer)) {
                            break;
                        }
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObject(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte currentByte = buffer.get();
            switch (currentByte) {
                // Explicit delimiters, handle push and pop in this method.
                case '{': {
                    frame = new Frame(frame, State.OBJECT, newObject(frame));
                    break;
                }
                case '}': {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> object = (Map<String, Object>)frame.value;
                    frame = frame.parent();
                    frame.value(convertObject(object));
                    return true;
                }
                case ',': {
                    break;
                }
                default: {
                    if (Character.isWhitespace(currentByte)) {
                        break;
                    } else {
                        buffer.position(buffer.position() - 1);
                        if (parseObjectField(buffer)) {
                            break;
                        }
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObjectField(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte peek = buffer.get(buffer.position());
            switch (peek) {
                case '"': {
                    if (frame.state == State.OBJECT) {
                        frame = new Frame(frame, State.OBJECT_FIELD, new NameValue());
                        if (parseObjectFieldName(buffer)) {
                            // We are not done yet, parse the value.
                            break;
                        }
                        return false;
                    } else {
                        return parseObjectFieldValue(buffer);
                    }
                }
                default: {
                    if (Character.isWhitespace(peek)) {
                        buffer.get();
                        break;
                    } else if (frame.state == State.OBJECT_FIELD_VALUE) {
                        return parseObjectFieldValue(buffer);
                    } else {
                        throw newInvalidJSON(buffer, "invalid object field");
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObjectFieldName(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte peek = buffer.get(buffer.position());
            switch (peek) {
                case '"': {
                    if (frame.state == State.OBJECT_FIELD) {
                        frame = new Frame(frame, State.OBJECT_FIELD_NAME, UNSET);
                        if (parseString(buffer)) {
                            // We are not done yet, parse until the ':'.
                            break;
                        }
                        return false;
                    } else {
                        throw newInvalidJSON(buffer, "invalid object field");
                    }
                }
                case ':': {
                    buffer.get();
                    // We are done with the field name.
                    String fieldName = (String)frame.value;
                    frame = frame.parent();
                    frame.value(fieldName);
                    // Change state to parse the field value.
                    frame = new Frame(frame, State.OBJECT_FIELD_VALUE, UNSET);
                    return true;
                }
                default: {
                    if (Character.isWhitespace(peek)) {
                        buffer.get();
                        break;
                    } else {
                        throw newInvalidJSON(buffer, "invalid object field");
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObjectFieldValue(ByteBuffer buffer) {
        if (frame.value == UNSET) {
            if (!parseAny(buffer)) {
                return false;
            }
        }
        // We are done with the field value.
        Object value = frame.value;
        frame = frame.parent();
        frame.value(value);
        // We are done with the field.
        value = frame.value;
        frame = frame.parent();
        frame.value(value);
        return true;
    }

    private Object convertObject(Map<String, Object> object) {
        Object result = convertObject("x-class", object);
        if (result == null) {
            result = convertObject("class", object);
            if (result == null) {
                return object;
            }
        }
        return result;
    }

    private Object convertObject(String fieldName, Map<String, Object> object) {
        String className = (String)object.get(fieldName);
        if (className == null) {
            return null;
        }

        JSON.Convertible convertible = toConvertible(className);
        if (convertible != null) {
            convertible.fromJSON(object);
            return convertible;
        }

        JSON.Convertor convertor = factory.convertors.get(className);
        if (convertor != null) {
            return convertor.fromJSON(object);
        }

        return null;
    }

    private JSON.Convertible toConvertible(String className) {
        try {
            Class<?> klass = Loader.loadClass(className);
            if (JSON.Convertible.class.isAssignableFrom(klass)) {
                return (JSON.Convertible)klass.getConstructor().newInstance();
            }
            return null;
        } catch (Throwable x) {
            throw new IllegalArgumentException(x);
        }
    }

    protected RuntimeException newInvalidJSON(ByteBuffer buffer, String message) {
        Utf8StringBuilder builder = new Utf8StringBuilder();
        builder.append(System.lineSeparator());
        int position = buffer.position();
        if (factory.isDebug()) {
            chunks.forEach(chunk -> builder.append(buffer));
        } else {
            buffer.position(0);
            builder.append(buffer);
            buffer.position(position);
        }
        builder.append(System.lineSeparator());
        String indent = "";
        if (position > 1) {
            char[] chars = new char[position - 1];
            Arrays.fill(chars, ' ');
            indent = new String(chars);
        }
        builder.append(indent);
        builder.append("^ ");
        builder.append(message);
        return new IllegalArgumentException(builder.toString());
    }

    public interface Context {
        public int depth();
        public Context parent();
    }

    private enum State {
        COMPLETE, NULL, TRUE, FALSE, NUMBER, STRING, ESCAPE, UNICODE, ARRAY, OBJECT, OBJECT_FIELD, OBJECT_FIELD_NAME, OBJECT_FIELD_VALUE
    }

    private static class Frame implements Context {
        private final Frame parent;
        private final State state;
        private Object value;

        private Frame(Frame parent, State state, Object value) {
            this.parent = parent;
            this.state = state;
            this.value = value;
        }

        @Override
        public int depth() {
            return AsyncJSON.depth(this);
        }

        @Override
        public Frame parent() {
            return parent;
        }

        private void value(Object value) {
            switch (state) {
                case COMPLETE:
                case STRING:
                case OBJECT_FIELD_NAME:
                case OBJECT_FIELD_VALUE: {
                    this.value = value;
                    break;
                }
                case ARRAY: {
                    @SuppressWarnings("unchecked")
                    List<Object> array = (List<Object>)this.value;
                    array.add(value);
                    break;
                }
                case OBJECT: {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> object = (Map<String, Object>)this.value;
                    NameValue nameValue = (NameValue)value;
                    object.put(nameValue.name, nameValue.value);
                    break;
                }
                case OBJECT_FIELD: {
                    NameValue nameValue = (NameValue)this.value;
                    if (nameValue.hasName) {
                        nameValue.value = value;
                    } else {
                        nameValue.hasName = true;
                        nameValue.name = (String)value;
                    }
                    break;
                }
                default: {
                    throw new IllegalStateException("invalid state " + state);
                }
            }
        }
    }

    private static class NameValue {
        private boolean hasName;
        private String name;
        private Object value;
    }

    private static class NumberBuilder {
        //  1 => positive integer
        //  0 => non-integer
        // -1 => negative integer
        private int integer = 1;
        private long value;
        private StringBuilder builder;

        private boolean appendSign(byte b) {
            if (integer == 0) {
                if (builder.length() == 0) {
                    builder.append((char)b);
                    return true;
                } else {
                    char c = builder.charAt(builder.length() - 1);
                    if (c == 'E' || c == 'e') {
                        builder.append((char)b);
                        return true;
                    }
                }
                return false;
            } else {
                if (value == 0) {
                    if (b == '-') {
                        if (integer == 1) {
                            integer = -1;
                            return true;
                        }
                    } else {
                        return true;
                    }
                }
            }
            return false;
        }

        private void appendDigit(byte b) {
            if (integer == 0) {
                builder.append((char)b);
            } else {
                value = value * 10 + (b - '0');
            }
        }

        private boolean appendAlpha(byte b) {
            if (integer == 0) {
                char c = builder.charAt(builder.length() - 1);
                if ('0' <= c && c <= '9' && builder.indexOf("" + (char)b) < 0) {
                    builder.append((char)b);
                    return true;
                }
            } else {
                builder = new StringBuilder(16);
                if (integer == -1) {
                    builder.append('-');
                }
                integer = 0;
                builder.append(value);
                builder.append((char)b);
                return true;
            }
            return false;
        }

        private Number value() {
            try {
                if (integer == 0) {
                    return Double.parseDouble(builder.toString());
                }
                return integer * value;
            } finally {
                reset();
            }
        }

        private void reset() {
            integer = 1;
            value = 0;
            builder = null;
        }
    }
}
