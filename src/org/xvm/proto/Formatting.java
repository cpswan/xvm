package org.xvm.proto;

import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Helpers.
 *
 * @author gg 2017.02.24
 */
public abstract class Formatting
    {
    public static String formatArray(Object[] ao, String sOpen, String sClose, String sDelim)
        {
        if (ao == null || ao.length == 0)
            {
            return "";
            }

        StringBuilder sb = new StringBuilder();
        sb.append(sOpen);

        boolean fFirst = true;
        for (Object o : ao)
            {
            if (fFirst)
                {
                fFirst = false;
                }
            else
                {
                sb.append(sDelim);
                }
            sb.append(o);
            }
        sb.append(sClose);
        return sb.toString();
        }

    public static String formatIterator(Iterator iter, String sOpen, String sClose, String sDelim)
        {
        if (!iter.hasNext())
            {
            return "";
            }

        StringBuilder sb = new StringBuilder();
        sb.append(sOpen);

        boolean fFirst = true;
        while (iter.hasNext())
            {
            if (fFirst)
                {
                fFirst = false;
                }
            else
                {
                sb.append(sDelim);
                }
            sb.append(iter.next());
            }
        sb.append(sClose);
        return sb.toString();
        }

    public static <K, V> String formatEntries(Map<K, V> map, String sDelim, BiFunction<K, V, String> formatEntry)
        {
        if (map == null || map.isEmpty())
            {
            return "";
            }

        StringBuilder sb = new StringBuilder();
        boolean fFirst = true;
        for (Map.Entry<K, V> entry : map.entrySet())
            {
            if (fFirst)
                {
                fFirst = false;
                }
            else
                {
                sb.append(sDelim);
                }
            sb.append(formatEntry.apply(entry.getKey(), entry.getValue()));
            }
        return sb.toString();
        }
    }
