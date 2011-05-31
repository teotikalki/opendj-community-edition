/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2010 Sun Microsystems, Inc.
 */

package org.forgerock.opendj.ldap.requests;

import org.forgerock.i18n.LocalizedIllegalArgumentException;
import org.forgerock.opendj.ldap.AttributeDescription;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DN;

/**
 * Unmodifiable compare request implementation.
 */
final class UnmodifiableCompareRequestImpl
    extends AbstractUnmodifiableRequest<CompareRequest>
    implements CompareRequest
{
  UnmodifiableCompareRequestImpl(CompareRequest impl) {
    super(impl);
  }

  public ByteString getAssertionValue() {
    return impl.getAssertionValue();
  }

  public String getAssertionValueAsString() {
    return impl.getAssertionValueAsString();
  }

  public AttributeDescription getAttributeDescription() {
    return impl.getAttributeDescription();
  }

  public DN getName() {
    return impl.getName();
  }

  public CompareRequest setAssertionValue(ByteString value)
      throws UnsupportedOperationException, NullPointerException {
    throw new UnsupportedOperationException();
  }

  public CompareRequest setAssertionValue(Object value)
      throws UnsupportedOperationException, NullPointerException {
    throw new UnsupportedOperationException();
  }

  public CompareRequest setAttributeDescription(
      AttributeDescription attributeDescription)
      throws UnsupportedOperationException, NullPointerException {
    throw new UnsupportedOperationException();
  }

  public CompareRequest setAttributeDescription(
      String attributeDescription)
      throws LocalizedIllegalArgumentException,
      UnsupportedOperationException, NullPointerException {
    throw new UnsupportedOperationException();
  }

  public CompareRequest setName(DN dn)
      throws UnsupportedOperationException, NullPointerException {
    throw new UnsupportedOperationException();
  }

  public CompareRequest setName(String dn)
      throws LocalizedIllegalArgumentException,
      UnsupportedOperationException, NullPointerException {
    throw new UnsupportedOperationException();
  }
}