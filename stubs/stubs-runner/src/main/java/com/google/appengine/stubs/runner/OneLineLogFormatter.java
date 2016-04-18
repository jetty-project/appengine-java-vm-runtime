package com.google.appengine.stubs.runner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class OneLineLogFormatter extends Formatter
{
    private StringWriter buf;
    private PrintWriter out;
    private final Date dat = new Date();

    public OneLineLogFormatter()
    {
        buf = new StringWriter();
        out = new PrintWriter(buf);
    }

    public synchronized String format(LogRecord record)
    {
        buf.getBuffer().setLength(0);

        dat.setTime(record.getMillis());
        out.printf("%1$tF %1$tT.%1$tL ", dat);
        out.printf("%s (%s) ", record.getLevel().getName(), Thread.currentThread().getName());
        String source;
        if (record.getSourceClassName() != null)
        {
            source = record.getSourceClassName();
            if (record.getSourceMethodName() != null)
            {
                source += " " + record.getSourceMethodName();
            }
        }
        else
        {
            source = record.getLoggerName();
        }
        out.printf("[%s]: %s", condensePackageString(source), formatMessage(record));

        if (record.getThrown() != null)
        {
            out.println();
            record.getThrown().printStackTrace(out);
        }

        out.println();

        return buf.toString();
    }

    protected static String condensePackageString(String classname)
    {
        String parts[] = classname.split("\\.");
        StringBuilder dense = new StringBuilder();
        for (int i = 0; i < (parts.length - 1); i++)
        {
            dense.append(parts[i].charAt(0));
        }
        if (dense.length() > 0)
        {
            dense.append('.');
        }
        dense.append(parts[parts.length - 1]);
        return dense.toString();
    }
}
