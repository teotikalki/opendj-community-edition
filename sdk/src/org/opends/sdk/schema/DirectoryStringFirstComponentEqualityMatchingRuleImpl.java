/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2009 Sun Microsystems, Inc.
 */
package org.opends.sdk.schema;



import static com.sun.opends.sdk.messages.Messages.*;
import static com.sun.opends.sdk.util.StringPrepProfile.*;

import org.opends.sdk.*;

import com.sun.opends.sdk.util.SubstringReader;



/**
 * This class implements the directoryStringFirstComponentMatch matching
 * rule defined in X.520 and referenced in RFC 2252. This rule is
 * intended for use with attributes whose values contain a set of
 * parentheses enclosing a space-delimited set of names and/or
 * name-value pairs (like attribute type or objectclass descriptions) in
 * which the "first component" is the first item after the opening
 * parenthesis.
 */
final class DirectoryStringFirstComponentEqualityMatchingRuleImpl
    extends AbstractMatchingRuleImpl
{
  @Override
  public Assertion getAssertion(Schema schema, ByteSequence value)
  {
    final StringBuilder buffer = new StringBuilder();
    prepareUnicode(buffer, value, TRIM, CASE_FOLD);

    final int bufferLength = buffer.length();
    if (bufferLength == 0)
    {
      if (value.length() > 0)
      {
        // This should only happen if the value is composed entirely of
        // spaces. In that case, the normalized value is a single space.
        return new DefaultEqualityAssertion(
            SchemaConstants.SINGLE_SPACE_VALUE);
      }
      else
      {
        // The value is empty, so it is already normalized.
        return new DefaultEqualityAssertion(ByteString.empty());
      }
    }

    // Replace any consecutive spaces with a single space.
    for (int pos = bufferLength - 1; pos > 0; pos--)
    {
      if (buffer.charAt(pos) == ' ')
      {
        if (buffer.charAt(pos - 1) == ' ')
        {
          buffer.delete(pos, pos + 1);
        }
      }
    }

    return new DefaultEqualityAssertion(ByteString.valueOf(buffer
        .toString()));
  }



  public ByteString normalizeAttributeValue(Schema schema,
      ByteSequence value) throws DecodeException
  {
    final String definition = value.toString();
    final SubstringReader reader = new SubstringReader(definition);

    // We'll do this a character at a time. First, skip over any leading
    // whitespace.
    reader.skipWhitespaces();

    if (reader.remaining() <= 0)
    {
      // This means that the value was empty or contained only
      // whitespace.
      // That is illegal.
      final LocalizableMessage message = ERR_ATTR_SYNTAX_EMPTY_VALUE.get();
      throw DecodeException.error(message);
    }

    // The next character must be an open parenthesis. If it is not,
    // then
    // that is an error.
    final char c = reader.read();
    if (c != '(')
    {
      final LocalizableMessage message =
          ERR_ATTR_SYNTAX_EXPECTED_OPEN_PARENTHESIS.get(definition,
              (reader.pos() - 1), String.valueOf(c));
      throw DecodeException.error(message);
    }

    // Skip over any spaces immediately following the opening
    // parenthesis.
    reader.skipWhitespaces();

    // The next set of characters must be the OID.
    final String string = SchemaUtils.readQuotedString(reader);

    // Grab the substring between the start pos and the current pos
    return ByteString.valueOf(string);
  }
}