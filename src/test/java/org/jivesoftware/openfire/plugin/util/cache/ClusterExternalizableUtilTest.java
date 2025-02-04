/*
 * Copyright (C) 2022-2025 Ignite Realtime Foundation. All rights reserved.
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
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

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

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(elements);
        oos.close();
        final byte[] buf = bos.toByteArray();

        final byte[] data = Bytes.concat(new byte[]{10}, intToByteArray(buf.length), buf);

        // Execute system under test.
        final DataInput in = new DataInputStream(new ByteArrayInputStream(data));
        final Object o = ClusterExternalizableUtil.readObject(in);

        // Verify results.
        Assert.assertTrue(o instanceof LinkedList);
        Assert.assertEquals(elements.size(), ((Collection) o).size());
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

        final ByteArrayOutputStream bos1 = new ByteArrayOutputStream();
        final ObjectOutputStream oos1 = new ObjectOutputStream(bos1);
        oos1.writeObject(elements.get(0));
        oos1.close();
        final byte[] buf1 = bos1.toByteArray();

        final ByteArrayOutputStream bos2 = new ByteArrayOutputStream();
        final ObjectOutputStream oos2 = new ObjectOutputStream(bos2);
        oos2.writeObject(elements.get(1));
        oos2.close();
        final byte[] buf2 = bos2.toByteArray();

        final byte[] data = Bytes.concat(new byte[]{2}, intToByteArray(elements.size()), new byte[] {10}, intToByteArray(buf1.length), buf1, new byte[] {10}, intToByteArray(buf2.length), buf2);

        // Execute system under test & verify results
        final DataInput in = new DataInputStream(new ByteArrayInputStream(data));
        Object o;
        o = ClusterExternalizableUtil.readObject(in);
        Assert.assertTrue(o instanceof Integer);
        o = ClusterExternalizableUtil.readObject(in);
        Assert.assertTrue(String.valueOf(o), o instanceof DefaultElement);
        Assert.assertEquals(elements.get(0).asXML(), ((DefaultElement)o).asXML());
        o = ClusterExternalizableUtil.readObject(in);
        Assert.assertTrue(o instanceof DefaultElement);
        Assert.assertEquals(elements.get(1).asXML(), ((DefaultElement)o).asXML());
    }

    private byte[] intToByteArray(final int i) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(i);
        dos.flush();
        return bos.toByteArray();
    }
}
