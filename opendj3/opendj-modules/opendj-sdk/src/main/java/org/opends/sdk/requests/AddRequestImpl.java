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
 *      Copyright 2010 Sun Microsystems, Inc.
 */

package org.opends.sdk.requests;



import java.util.Collection;

import org.opends.sdk.*;
import org.opends.sdk.ldif.ChangeRecordVisitor;



/**
 * Add request implementation.
 */
final class AddRequestImpl extends AbstractRequestImpl<AddRequest> implements
    AddRequest
{

  private final Entry entry;



  /**
   * Creates a new add request backed by the provided entry. Modifications made
   * to {@code entry} will be reflected in the returned add request. The
   * returned add request supports updates to its list of controls, as well as
   * updates to the name and attributes if the underlying entry allows.
   *
   * @param entry
   *          The entry to be added.
   * @throws NullPointerException
   *           If {@code entry} was {@code null} .
   */
  AddRequestImpl(final Entry entry) throws NullPointerException
  {
    this.entry = entry;
  }



  /**
   * Creates a new add request that is an exact copy of the provided
   * request.
   *
   * @param addRequest
   *          The add request to be copied.
   * @throws NullPointerException
   *           If {@code addRequest} was {@code null} .
   */
  AddRequestImpl(final AddRequest addRequest) throws NullPointerException
  {
    super(addRequest);
    this.entry = LinkedHashMapEntry.deepCopyOfEntry(addRequest);
  }



  /**
   * {@inheritDoc}
   */
  public <R, P> R accept(final ChangeRecordVisitor<R, P> v, final P p)
  {
    return v.visitChangeRecord(p, this);
  }



  /**
   * {@inheritDoc}
   */
  public boolean addAttribute(final Attribute attribute)
      throws UnsupportedOperationException, NullPointerException
  {
    return entry.addAttribute(attribute);
  }



  /**
   * {@inheritDoc}
   */
  public boolean addAttribute(final Attribute attribute,
      final Collection<ByteString> duplicateValues)
      throws UnsupportedOperationException, NullPointerException
  {
    return entry.addAttribute(attribute, duplicateValues);
  }



  /**
   * {@inheritDoc}
   */
  public AddRequest addAttribute(final String attributeDescription,
      final Object... values) throws LocalizedIllegalArgumentException,
      UnsupportedOperationException, NullPointerException
  {
    entry.addAttribute(attributeDescription, values);
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public AddRequest clearAttributes() throws UnsupportedOperationException
  {
    entry.clearAttributes();
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public boolean containsAttribute(final Attribute attribute,
      final Collection<ByteString> missingValues) throws NullPointerException
  {
    return entry.containsAttribute(attribute, missingValues);
  }



  /**
   * {@inheritDoc}
   */
  public boolean containsAttribute(final String attributeDescription,
      final Object... values) throws LocalizedIllegalArgumentException,
      NullPointerException
  {
    return entry.containsAttribute(attributeDescription, values);
  }



  /**
   * {@inheritDoc}
   */
  public Iterable<Attribute> getAllAttributes()
  {
    return entry.getAllAttributes();
  }



  /**
   * {@inheritDoc}
   */
  public Iterable<Attribute> getAllAttributes(
      final AttributeDescription attributeDescription)
      throws NullPointerException
  {
    return entry.getAllAttributes(attributeDescription);
  }



  /**
   * {@inheritDoc}
   */
  public Iterable<Attribute> getAllAttributes(final String attributeDescription)
      throws LocalizedIllegalArgumentException, NullPointerException
  {
    return entry.getAllAttributes(attributeDescription);
  }



  /**
   * {@inheritDoc}
   */
  public Attribute getAttribute(final AttributeDescription attributeDescription)
      throws NullPointerException
  {
    return entry.getAttribute(attributeDescription);
  }



  /**
   * {@inheritDoc}
   */
  public Attribute getAttribute(final String attributeDescription)
      throws LocalizedIllegalArgumentException, NullPointerException
  {
    return entry.getAttribute(attributeDescription);
  }



  /**
   * {@inheritDoc}
   */
  public int getAttributeCount()
  {
    return entry.getAttributeCount();
  }



  /**
   * {@inheritDoc}
   */
  public DN getName()
  {
    return entry.getName();
  }



  /**
   * {@inheritDoc}
   */
  public boolean removeAttribute(final Attribute attribute,
      final Collection<ByteString> missingValues)
      throws UnsupportedOperationException, NullPointerException
  {
    return entry.removeAttribute(attribute, missingValues);
  }



  /**
   * {@inheritDoc}
   */
  public boolean removeAttribute(final AttributeDescription attributeDescription)
      throws UnsupportedOperationException, NullPointerException
  {
    return entry.removeAttribute(attributeDescription);
  }



  /**
   * {@inheritDoc}
   */
  public AddRequest removeAttribute(final String attributeDescription,
      final Object... values) throws LocalizedIllegalArgumentException,
      UnsupportedOperationException, NullPointerException
  {
    entry.removeAttribute(attributeDescription, values);
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public boolean replaceAttribute(final Attribute attribute)
      throws UnsupportedOperationException, NullPointerException
  {
    return entry.replaceAttribute(attribute);
  }



  /**
   * {@inheritDoc}
   */
  public AddRequest replaceAttribute(final String attributeDescription,
      final Object... values) throws LocalizedIllegalArgumentException,
      UnsupportedOperationException, NullPointerException
  {
    entry.replaceAttribute(attributeDescription, values);
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public AddRequest setName(final DN dn) throws UnsupportedOperationException,
      NullPointerException
  {
    entry.setName(dn);
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public AddRequest setName(final String dn)
      throws LocalizedIllegalArgumentException, UnsupportedOperationException,
      NullPointerException
  {
    entry.setName(dn);
    return this;
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public String toString()
  {
    final StringBuilder builder = new StringBuilder();
    builder.append("AddRequest(name=");
    builder.append(getName());
    builder.append(", attributes=");
    builder.append(getAllAttributes());
    builder.append(", controls=");
    builder.append(getControls());
    builder.append(")");
    return builder.toString();
  }



  /**
   * {@inheritDoc}
   */
  @Override
  AddRequest getThis()
  {
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode()
  {
    return entry.hashCode();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(final Object object)
  {
    return entry.equals(object);
  }

}