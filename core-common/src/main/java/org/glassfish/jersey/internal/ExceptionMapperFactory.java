/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.internal;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.ext.ExceptionMapper;

import org.glassfish.jersey.internal.inject.AbstractModule;
import org.glassfish.jersey.internal.inject.ReferencingFactory;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.internal.util.collection.ClassTypePair;
import org.glassfish.jersey.internal.util.collection.Ref;
import org.glassfish.jersey.process.internal.RequestScope;
import org.glassfish.jersey.spi.ExceptionMappers;

import org.glassfish.hk2.Factory;
import org.glassfish.hk2.Scope;
import org.glassfish.hk2.TypeLiteral;

import org.jvnet.hk2.annotations.Inject;

/**
 * ExceptionMapperFactory class.
 *
 * @author Paul Sandoz
 * @author Santiago.PericasGeertsen@oracle.com
 */
public class ExceptionMapperFactory implements ExceptionMappers {
    private static final Logger LOGGER = Logger.getLogger(ExceptionMapperFactory.class.getName());

    public static class Module extends AbstractModule {

        private static class InjectionFactory extends ReferencingFactory<ExceptionMappers> {

            public InjectionFactory(@Inject Factory<Ref<ExceptionMappers>> referenceFactory) {
                super(referenceFactory);
            }
        }
        //
        private final Class<? extends Scope> refScope;

        public Module(Class<? extends Scope> refScope) {
            this.refScope = refScope;
        }

        @Override
        protected void configure() {
            bind(ExceptionMappers.class)
                    .toFactory(InjectionFactory.class)
                    .in(RequestScope.class);
            bind(new TypeLiteral<Ref<ExceptionMappers>>() {})
                    .toFactory(ReferencingFactory.<ExceptionMappers>referenceFactory())
                    .in(refScope);
        }
    }

    private static class ExceptionMapperType {

        ExceptionMapper em;
        Class<? extends Throwable> c;

        public ExceptionMapperType(ExceptionMapper em, Class<? extends Throwable> c) {
            this.em = em;
            this.c = c;
        }
    }
    private Set<ExceptionMapperType> emts = new HashSet<ExceptionMapperType>();

    public ExceptionMapperFactory(Set<ExceptionMapper> mappers) {
        for (ExceptionMapper<?> em : mappers) {
            Class<? extends Throwable> c = getExceptionType(em.getClass());
            if (c != null) {
                emts.add(new ExceptionMapperType(em, c));
            }
        }
    }

    public ExceptionMapperFactory(ServiceProviders serviceProviders) {
        this(serviceProviders.getAll(ExceptionMapper.class));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Throwable> ExceptionMapper<T> find(Class<T> type) {
        int distance = Integer.MAX_VALUE;
        ExceptionMapper selectedEm = null;
        for (ExceptionMapperType emt : emts) {
            int d = distance(type, emt.c);
            if (d < distance) {
                distance = d;
                selectedEm = emt.em;
                if (distance == 0) {
                    break;
                }
            }
        }

        return selectedEm;
    }

    private int distance(Class<?> c, Class<?> emtc) {
        int distance = 0;
        if (!emtc.isAssignableFrom(c)) {
            return Integer.MAX_VALUE;
        }

        while (c != emtc) {
            c = c.getSuperclass();
            distance++;
        }

        return distance;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Throwable> getExceptionType(Class<? extends ExceptionMapper> c) {
        Class<?> t = getType(c);
        if (Throwable.class.isAssignableFrom(t)) {
            return (Class<? extends Throwable>) t;
        }

        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(LocalizationMessages.EXCEPTION_MAPPER_SUPPORTED_TYPE_UNKNOWN(c.getName()));
        }

        return null;
    }

    private Class getType(Class<? extends ExceptionMapper> c) {
        Class _c = c;
        while (_c != Object.class) {
            Type[] ts = _c.getGenericInterfaces();
            for (Type t : ts) {
                if (t instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) t;
                    if (pt.getRawType() == ExceptionMapper.class) {
                        return getResolvedType(pt.getActualTypeArguments()[0], c, _c);
                    }
                }
            }

            _c = _c.getSuperclass();
        }

        // This statement will never be reached
        return null;
    }

    private Class getResolvedType(Type t, Class c, Class dc) {
        if (t instanceof Class) {
            return (Class) t;
        } else if (t instanceof TypeVariable) {
            ClassTypePair ct = ReflectionHelper.resolveTypeVariable(c, dc, (TypeVariable) t);
            if (ct != null) {
                return ct.getClass();
            } else {
                return null;
            }
        } else if (t instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) t;
            return (Class) pt.getRawType();
        } else {
            return null;
        }
    }
}