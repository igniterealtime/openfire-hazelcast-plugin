/*
 * Copyright (C) 2022-2026 Ignite Realtime Foundation. All rights reserved.
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
package org.jivesoftware.openfire.plugin.util.cache;

import com.google.common.primitives.Bytes;
import org.dom4j.tree.DefaultElement;
import org.jivesoftware.util.cache.CacheFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.io.*;
import java.util.*;

public class ClusterExternalizableUtilTest
{
    @BeforeClass
    public static void setup() throws Exception {
        CacheFactory.initialize();
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() successfully deserializes a Collection of Messages.
     */
    @Test
    public void testReadObjectWithCollection() throws Exception
    {
        // Setup test fixture.
        final Message m1 = new Message();
        m1.setBody("test one two three");
        m1.setTo(new JID("foo", "example.org", "test"));
        m1.setFrom(new JID("bar", "example.org", "barfoo"));
        final Message m2 = new Message();
        m2.setBody("The brown fox jumped over the ... something");
        m2.setTo(new JID("john", "example.com", "aw23r2"));
        m2.setFrom(new JID("jane", "example.net", "Ψ+"));

        final List<DefaultElement> elements = new LinkedList<>();
        elements.add((DefaultElement) m1.getElement());
        elements.add((DefaultElement) m2.getElement());

        final byte[] buf;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(elements);
            buf = bos.toByteArray();
        }
        final byte[] data = Bytes.concat(new byte[]{10}, intToByteArray(buf.length), buf);

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertTrue(result instanceof LinkedList);
        Assert.assertEquals(elements.size(), ((Collection) result).size());
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() successfully deserializes multiple instances.
     * Collectively, the instances make up for a Collection of Messages equal to that used in {@link #testReadObjectWithCollection()}
     */
    @Test
    public void testReadObjectWithMultipleInstances() throws Exception
    {
        // Setup test fixture.
        final Message m1 = new Message();
        m1.setBody("test one two three");
        m1.setTo(new JID("foo", "example.org", "test"));
        m1.setFrom(new JID("bar", "example.org", "barfoo"));
        final Message m2 = new Message();
        m2.setBody("The brown fox jumped over the ... something");
        m2.setTo(new JID("john", "example.com", "aw23r2"));
        m2.setFrom(new JID("jane", "example.net", "Ψ+"));

        final List<DefaultElement> elements = new LinkedList<>();
        elements.add((DefaultElement) m1.getElement());
        elements.add((DefaultElement) m2.getElement());

        final byte[] buf1;
        try (final ByteArrayOutputStream bos1 = new ByteArrayOutputStream();
             final ObjectOutputStream oos1 = new ObjectOutputStream(bos1)) {
            oos1.writeObject(elements.get(0));
            buf1 = bos1.toByteArray();
        }

        final byte[] buf2;
        try (final ByteArrayOutputStream bos2 = new ByteArrayOutputStream();
             final ObjectOutputStream oos2 = new ObjectOutputStream(bos2)) {
            oos2.writeObject(elements.get(1));
            buf2 = bos2.toByteArray();
        }
        final byte[] data = Bytes.concat(new byte[]{2}, intToByteArray(elements.size()), new byte[] {10}, intToByteArray(buf1.length), buf1, new byte[] {10}, intToByteArray(buf2.length), buf2);

        // Execute system under test & verify results
        Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
            Assert.assertTrue(result instanceof Integer);
            result = ClusterExternalizableUtil.readObject(in);
            Assert.assertTrue(String.valueOf(result), result instanceof DefaultElement);
            Assert.assertEquals(elements.get(0).asXML(), ((DefaultElement)result).asXML());
            result = ClusterExternalizableUtil.readObject(in);
        }
        Assert.assertTrue(result instanceof DefaultElement);
        Assert.assertEquals(elements.get(1).asXML(), ((DefaultElement)result).asXML());
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() returns null when the type tag indicates a null value (tag 0).
     */
    @Test
    public void testReadObjectNull() throws Exception
    {
        // Setup test fixture.
        final byte[] data = new byte[]{0};

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertNull(result);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() correctly deserializes a Long value (tag 1).
     */
    @Test
    public void testReadObjectLong() throws Exception
    {
        // Setup test fixture.
        final long value = Long.MIN_VALUE;
        final byte[] data;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos) ) {
            dos.writeByte(1);
            dos.writeLong(value);
            dos.flush();
            data = bos.toByteArray();
        }

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertTrue(result instanceof Long);
        Assert.assertEquals(value, result);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() correctly deserializes a String value (tag 3).
     */
    @Test
    public void testReadObjectString() throws Exception
    {
        // Setup test fixture.
        final String value = "Test String With Unicode: \uD834\uDD1E";
        final byte[] data;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(3);
            dos.writeUTF(value);
            dos.flush();
            data = bos.toByteArray();
        }

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertEquals(value, result);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() correctly deserializes a Double value (tag 4).
     */
    @Test
    public void testReadObjectDouble() throws Exception
    {
        // Setup test fixture.
        final double value = Double.MAX_VALUE;
        final byte[] data;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(4);
            dos.writeDouble(value);
            dos.flush();
            data = bos.toByteArray();
        }

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertTrue(result instanceof Double);
        Assert.assertEquals(value, (Double) result, 0.0);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() correctly deserializes a Float value (tag 5).
     */
    @Test
    public void testReadObjectFloat() throws Exception
    {
        // Setup test fixture.
        final float value = Float.MAX_VALUE;
        final byte[] data;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(5);
            dos.writeFloat(value);
            dos.flush();
            data = bos.toByteArray();
        }

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertTrue(result instanceof Float);
        Assert.assertEquals(value, (Float) result, 0.0f);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() correctly deserializes a Boolean value (tag 6).
     */
    @Test
    public void testReadObjectBoolean() throws Exception
    {
        // Setup test fixture.
        final byte[] data;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(6);
            dos.writeBoolean(true);
            dos.flush();
            data = bos.toByteArray();
        }

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertTrue(result instanceof Boolean);
        Assert.assertEquals(Boolean.TRUE, result);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() correctly deserializes a Date value (tag 8).
     */
    @Test
    public void testReadObjectDate() throws Exception
    {
        // Setup test fixture.
        final Date value = new Date(1_000_000_000_000L);
        final byte[] data;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(8);
            dos.writeLong(value.getTime());
            dos.flush();
            data = bos.toByteArray();
        }

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertTrue(result instanceof Date);
        Assert.assertEquals(value, result);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() correctly deserializes a byte array (tag 9).
     */
    @Test
    public void testReadObjectByteArray() throws Exception
    {
        // Setup test fixture.
        final byte[] value = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        final byte[] data;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(9);
            dos.writeInt(value.length);
            dos.write(value);
            dos.flush();
            data = bos.toByteArray();
        }

        // Execute system under test.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertArrayEquals(value, (byte[]) result);
    }

    /**
     * Verifies that ClusterExternalizableUtil.readObject() throws an IOException for an unrecognised type tag.
     */
    @Test(expected = IOException.class)
    public void testReadObjectUnknownTypeTag() throws Exception
    {
        // Setup test fixture: type tag 7 is not assigned.
        final byte[] data = new byte[]{7};

        // Execute system under test.
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            ClusterExternalizableUtil.readObject(in);
        }
    }

    /**
     * Verifies that a Map with non-String Serializable keys survives a write/read round-trip through
     * writeSerializableMap/readSerializableMap. This specifically exercises the fix for the bug where the
     * deserialized result was incorrectly narrowed to {@code Map<String, Serializable>}.
     */
    @Test
    public void testWriteReadSerializableMapNonStringKeys() throws Exception
    {
        // Setup test fixture.
        final Map<Integer, UUID> input = new HashMap<>();
        input.put(1, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        input.put(2, UUID.fromString("00000000-0000-0000-0000-000000000002"));

        final Bus bus = new Bus();
        new ClusterExternalizableUtil().writeSerializableMap(bus.getDataOutput(), input);

        // Execute system under test.
        final Map<Integer, UUID> result = new HashMap<>();
        final int reportedCount = new ClusterExternalizableUtil().readSerializableMap(
            bus.getDataInput(), result, getClass().getClassLoader());

        // Verify results.
        Assert.assertEquals(2, reportedCount);
        Assert.assertEquals(input, result);
    }

    /**
     * Verifies that deserializing an object for the first time registers its class in the cache.
     */
    @Test
    public void testFirstDeserializationPopulatesClassCache() throws Exception
    {
        ClusterExternalizableUtil.purgeCachedClassDefinitions();

        final byte[] buf;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            buf = bos.toByteArray();
        }

        final byte[] data = Bytes.concat(
            new byte[]{10}, intToByteArray(buf.length), buf
        );

        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            ClusterExternalizableUtil.readObject(in);
        }

        Assert.assertTrue(
            "Expected UUID class to be cached after first deserialization",
            ClusterExternalizableUtil.isClassCached(UUID.class.getName())
        );
    }

    /**
     * Verifies that a second deserialization of the same class succeeds when the class definition
     * is served from cache rather than resolved again from the stream or classloader.
     */
    @Test
    public void testCachedClassDefinitionIsUsedForSubsequentDeserialization() throws Exception
    {
        ClusterExternalizableUtil.purgeCachedClassDefinitions();

        // Prime the cache with the first object.
        final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        final byte[] firstData;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(first);
            firstData = bos.toByteArray();
        }

        final byte[] secondData;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(second);
            secondData = bos.toByteArray();
        }

        final byte[] data = Bytes.concat(
            new byte[]{10}, intToByteArray(firstData.length), firstData,
            new byte[]{10}, intToByteArray(secondData.length), secondData
        );

        final Object o1;
        final Object o2;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            o1 = ClusterExternalizableUtil.readObject(in);
            // At this point the cache must contain UUID — the second read depends on it.
            Assert.assertTrue(ClusterExternalizableUtil.isClassCached(UUID.class.getName()));
            o2 = ClusterExternalizableUtil.readObject(in);
        }

        Assert.assertEquals(first, o1);
        Assert.assertEquals(second, o2);
    }

    /**
     * Verifies that after calling purgeCachedClassDefinitions, deserialization still succeeds (the cache
     * miss is handled gracefully by reloading the class).
     */
    @Test
    public void testPurgeCachedClassDefinitionsAllowsSubsequentDeserialization() throws Exception
    {
        // Setup test fixture.
        final UUID value = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final byte[] buf;
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final ObjectOutputStream boo = new ObjectOutputStream(bos)) {
            boo.writeObject(value);
            buf = bos.toByteArray();
        }
        final byte[] data = Bytes.concat(new byte[]{10}, intToByteArray(buf.length), buf);

        // Populate cache, then purge it.
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            ClusterExternalizableUtil.readObject(in);
        }
        ClusterExternalizableUtil.purgeCachedClassDefinitions();

        // Execute system under test: should succeed despite empty cache.
        final Object result;
        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            result = ClusterExternalizableUtil.readObject(in);
        }

        // Verify results.
        Assert.assertEquals(value, result);
    }

    private byte[] intToByteArray(final int i) throws IOException {
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(i);
            dos.flush();
            return bos.toByteArray();
        }
    }

    /**
     * Utility class that generates a DataOutput and DataInput that share a common resource, meaning that what is
     * written to the DataOutput becomes available for reading on the DataInput.
     *
     * Instances are single-use. DataOutput should be written to before DataInput is obtained.
     */
    public static class Bus
    {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        public DataOutput getDataOutput() {
            return new DataOutputStream(baos);
        }

        public DataInput getDataInput() {
            return new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        }
    }
}
