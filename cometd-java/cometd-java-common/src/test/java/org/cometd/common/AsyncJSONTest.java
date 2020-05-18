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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Enclosed.class)
public class AsyncJSONTest {
    private static <T> AsyncJSON<T> newAsyncJSON(Consumer<T> consumer) {
        AsyncJSON.Factory factory = new AsyncJSON.Factory();
        factory.setDebug(true);
        return factory.newAsyncJSON(consumer);
    }

    private static Throwable assertThrows(Class<? extends Throwable> failureClass, Runnable test) {
        try {
            test.run();
            throw new AssertionError("Expected " + failureClass.getSimpleName());
        } catch (Exception x) {
            if (failureClass.isInstance(x)) {
                return x;
            }
            throw new AssertionError(x);
        }
    }

    @RunWith(Parameterized.class)
    public static class InvalidJSONTest {
        @Parameterized.Parameters(name = "''{0}''")
        public static List<String> strings() {
            return Arrays.asList("|", "}", "]", "{]", "[}", "+", ".");
        }

        @Parameterized.Parameter
        public String json;

        @Test
        public void testParseInvalidJSON() {
            byte[] bytes = json.getBytes(UTF_8);
            AsyncJSON<String> parser = newAsyncJSON(result -> {});

            // Parse the whole input.
            assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes));
            assertEquals(0, parser.depth());

            // Parse byte by byte.
            assertThrows(IllegalArgumentException.class, () -> {
                for (byte b : bytes) {
                    parser.parse(new byte[]{b});
                }
            });
            assertEquals(0, parser.depth());
        }
    }

    @RunWith(Parameterized.class)
    public static class ValidStringTest {
        @Parameterized.Parameters(name = "''{0}''")
        public static List<Object[]> strings() {
            List<Object[]> result = new ArrayList<>();
            result.add(new Object[]{"", ""});
            result.add(new Object[]{" \t\r\n", " \t\r\n"});
            result.add(new Object[]{"\u20AC", "\u20AC"});
            result.add(new Object[]{"\\u20AC", "\u20AC"});
            result.add(new Object[]{"/foo", "/foo"});
            result.add(new Object[]{"123E+01", "123E+01"});
            result.add(new Object[]{"A\\u20AC/foo\\t\\n", "A\u20AC/foo\t\n"});
            return result;
        }

        @Parameterized.Parameter
        public String string;
        @Parameterized.Parameter(1)
        public String expected;

        @Test
        public void testParseString() {
            String json = "\"${value}\"".replace("${value}", string);
            byte[] bytes = json.getBytes(UTF_8);
            AsyncJSON<String> parser = newAsyncJSON(result -> assertEquals(expected, result));

            // Parse the whole input.
            assertTrue(parser.parse(bytes));
            assertEquals(0, parser.depth());

            // Parse byte by byte.
            for (int i = 0; i < bytes.length; ++i) {
                byte b = bytes[i];
                if (i == bytes.length - 1) {
                    assertTrue(parser.parse(new byte[]{b}));
                } else {
                    assertFalse(parser.parse(new byte[]{b}));
                }
            }
            assertEquals(0, parser.depth());
        }
    }

    @RunWith(Parameterized.class)
    public static class InvalidStringTest {
        @Parameterized.Parameters(name = "''{0}''")
        public static List<String> strings() {
            return Arrays.asList("\\u", "\\u0", "\\x");
        }

        @Parameterized.Parameter
        public String value;

        @Test
        public void testParseInvalidString() {
            String json = "\"${value}\"".replace("${value}", value);
            byte[] bytes = json.getBytes(UTF_8);
            AsyncJSON<String> parser = newAsyncJSON(result -> assertEquals(value, result));

            // Parse the whole input.
            assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes));
            assertEquals(0, parser.depth());

            // Parse byte by byte.
            assertThrows(IllegalArgumentException.class, () -> {
                for (byte b : bytes) {
                    parser.parse(new byte[]{b});
                }
            });
            assertEquals(0, parser.depth());
        }
    }

    @RunWith(Parameterized.class)
    public static class ValidArrayTest {
        @Parameterized.Parameters(name = "''{0}''")
        public static List<Object[]> strings() {
            List<Object[]> result = new ArrayList<>();

            List<Object> expected = Collections.emptyList();
            result.add(new Object[]{"[]", expected});

            expected = new ArrayList<>();
            expected.add(Collections.emptyList());
            result.add(new Object[]{"[[]]", expected});

            expected = new ArrayList<>();
            expected.add("first");
            expected.add(5D);
            expected.add(null);
            expected.add(true);
            expected.add(false);
            expected.add(new HashMap<>());
            HashMap<String, Object> last = new HashMap<>();
            last.put("a", new ArrayList<>());
            expected.add(last);
            result.add(new Object[]{"[\"first\", 5E+0, null, true, false, {}, {\"a\":[]}]", expected});

            return result;
        }

        @Parameterized.Parameter
        public String json;
        @Parameterized.Parameter(1)
        public List<Object> expected;

        @Test
        public void testParseArray() {
            byte[] bytes = json.getBytes(UTF_8);
            AsyncJSON<List<Object>> parser = newAsyncJSON(result -> assertEquals(expected, result));

            // Parse the whole input.
            assertTrue(parser.parse(bytes));
            assertEquals(0, parser.depth());

            // Parse byte by byte.
            for (int i = 0; i < bytes.length; ++i) {
                byte b = bytes[i];
                if (i == bytes.length - 1) {
                    assertTrue(parser.parse(new byte[]{b}));
                } else {
                    assertFalse(parser.parse(new byte[]{b}));
                }
            }
            assertEquals(0, parser.depth());
        }
    }

    @RunWith(Parameterized.class)
    public static class ValidObjectTest {
        @Parameterized.Parameters(name = "''{0}''")
        public static List<Object[]> strings() {
            List<Object[]> result = new ArrayList<>();

            HashMap<String, Object> expected = new HashMap<>();
            result.add(new Object[]{"{}", expected});

            expected = new HashMap<>();
            expected.put("name", "value");
            result.add(new Object[]{"{ \"name\": \"value\" }", expected});

            expected = new HashMap<>();
            expected.put("name", null);
            expected.put("valid", true);
            expected.put("secure", false);
            expected.put("value", 42L);
            result.add(new Object[]{"{, \"name\": null, \"valid\": true\n , \"secure\": false\r\n,\n \"value\":42, }", expected});

            return result;
        }

        @Parameterized.Parameter
        public String json;
        @Parameterized.Parameter(1)
        public Map<String, Object> expected;

        @Test
        public void testParseObject() {
            byte[] bytes = json.getBytes(UTF_8);
            AsyncJSON<Map<String, Object>> parser = newAsyncJSON(result -> assertEquals(expected, result));

            // Parse the whole input.
            assertTrue(parser.parse(bytes));
            assertEquals(0, parser.depth());

            // Parse byte by byte.
            for (int i = 0; i < bytes.length; ++i) {
                byte b = bytes[i];
                if (i == bytes.length - 1) {
                    assertTrue(parser.parse(new byte[]{b}));
                } else {
                    assertFalse(parser.parse(new byte[]{b}));
                }
            }
            assertEquals(0, parser.depth());
        }
    }

    @RunWith(Parameterized.class)
    public static class ValidNumberTest {
        @Parameterized.Parameters(name = "{0}")
        public static List<Object[]> strings() {
            List<Object[]> result = new ArrayList<>();

            result.add(new Object[]{"0", 0L});
            result.add(new Object[]{"-0", -0L});
            result.add(new Object[]{"13", 13L});
            result.add(new Object[]{"-42", -42L});
            result.add(new Object[]{"123.456", 123.456D});
            result.add(new Object[]{"-234.567", -234.567D});
            result.add(new Object[]{"9e0", 9D});
            result.add(new Object[]{"8E+1", 80D});
            result.add(new Object[]{"70.5E-1", 7.05D});

            return result;
        }

        @Parameterized.Parameter
        public String json;
        @Parameterized.Parameter(1)
        public Number expected;

        @Test
        public void testParseNumber() {
            byte[] bytes = json.getBytes(UTF_8);
            AsyncJSON<Number> parser = newAsyncJSON(result -> assertEquals(expected, result));

            // Parse the whole input.
            assertFalse(parser.parse(bytes));
            parser.close();
            assertEquals(0, parser.depth());

            // Parse byte by byte.
            for (byte b : bytes) {
                assertFalse(parser.parse(new byte[]{b}));
            }
            parser.close();
            assertEquals(0, parser.depth());
        }
    }

    public static class ContextTest {
        @Test
        public void testContext() {
            AsyncJSON.Factory factory = new AsyncJSON.Factory() {
                @Override
                public <T> AsyncJSON<T> newAsyncJSON(Consumer<T> onComplete) {
                    return new AsyncJSON<T>(this, onComplete) {
                        @Override
                        protected HashMap<String, Object> newObject(Context context) {
                            if (context.depth() == 1) {
                                return new HashMapMessage();
                            }
                            return super.newObject(context);
                        }
                    };
                }
            };
            AtomicReference<List<Message.Mutable>> ref = new AtomicReference<>();
            AsyncJSON<List<Message.Mutable>> parser = factory.newAsyncJSON(ref::set);

            String json = "[{" +
                    "\"channel\": \"/meta/handshake\"," +
                    "\"version\": \"1.0\"," +
                    "\"supportedConnectionTypes\": [\"long-polling\"]," +
                    "\"advice\": {\"timeout\": 0}" +
                    "}]";

            parser.parse(UTF_8.encode(json));
            List<Message.Mutable> messages = ref.get();

            for (Message.Mutable message : messages) {
                assertTrue(message instanceof HashMapMessage);
                Map<String, Object> advice = message.getAdvice(false);
                assertFalse(advice instanceof HashMapMessage);
            }
        }
    }

    public static class ConvertorTest {
        @Test
        public void testParseObjectWithConvertor() {
            AsyncJSON.Factory factory = new AsyncJSON.Factory();
            factory.addConvertor(CustomConvertor.class.getName(), new CustomConvertor());

            String json = "{" +
                    "\"f1\": {\"class\":\"" + CustomConvertible.class.getName() + "\", \"field\": \"value\"}," +
                    "\"f2\": {\"class\":\"" + CustomConvertor.class.getName() + "\"}" +
                    "}";

            AtomicReference<Map<String, Object>> ref = new AtomicReference<>();
            AsyncJSON<Map<String, Object>> parser = factory.newAsyncJSON(ref::set);
            parser.parse(UTF_8.encode(json));
            Map<String, Object> result = ref.get();

            Object value1 = result.get("f1");
            assertTrue(value1 instanceof CustomConvertible);
            assertEquals("value", ((CustomConvertible)value1).field);
            assertTrue(result.get("f2") instanceof CustomConvertor.Custom);
        }
    }

    @Ignore
    public static class CustomConvertible implements JSON.Convertible {
        private Object field;

        @Override
        public void toJSON(JSON.Output out) {
        }

        @Override
        public void fromJSON(Map map) {
            this.field = map.get("field");
        }
    }

    @Ignore
    public static class CustomConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
        }

        @Override
        public Object fromJSON(Map map) {
            return new Custom();
        }

        public static class Custom {
        }
    }
}
