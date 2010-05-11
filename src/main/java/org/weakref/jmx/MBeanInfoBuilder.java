/**
 *  Copyright 2009 Martin Traverso
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.weakref.jmx;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.Descriptor;
import javax.management.IntrospectionException;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.modelmbean.DescriptorSupport;
import javax.management.modelmbean.ModelMBeanAttributeInfo;
import javax.management.modelmbean.ModelMBeanConstructorInfo;
import javax.management.modelmbean.ModelMBeanInfo;
import javax.management.modelmbean.ModelMBeanInfoSupport;
import javax.management.modelmbean.ModelMBeanNotificationInfo;
import javax.management.modelmbean.ModelMBeanOperationInfo;

import com.thoughtworks.paranamer.BytecodeReadingParanamer;
import com.thoughtworks.paranamer.Paranamer;

class MBeanInfoBuilder
{
    private final static Pattern getterOrSetterPattern = Pattern.compile("(get|set|is)(.+)");

    private final static String DESCRIPTION = "org.weakref.jmx.descriptor.description";
    private final static String METHOD_INSTANCE = "org.weakref.jmx.descriptor.methodInstance";
    private final static String GET_METHOD_INSTANCE = "org.weakref.jmx.descriptor.getMethodInstance";
    private final static String SET_METHOD_INSTANCE = "org.weakref.jmx.descriptor.setMethodInstance";

    public ModelMBeanInfo buildInfo(Class clazz)
            throws IntrospectionException
    {
        Map<String, DescriptorSupport> attributes = new HashMap<String, DescriptorSupport>();
        List<DescriptorSupport> operations = new ArrayList<DescriptorSupport>();

        AnnotationFinder resolver = new AnnotationFinder();

        Map<Method, Annotation> methods = resolver.findAnnotatedMethods(clazz);
        for (Map.Entry<Method, Annotation> entry : methods.entrySet()) {
            Method method = entry.getKey();
            Annotation annotation = entry.getValue();

            DescriptorSupport operationDescriptor = new DescriptorSupport();
            operationDescriptor.setField("name", method.getName());
            operationDescriptor.setField("class", clazz.getName());
            operationDescriptor.setField("descriptorType", "operation");
            String description = "";
            try {
                Method descriptionMethod = annotation.annotationType().getMethod("description");
                description = descriptionMethod.invoke(annotation).toString();
                operationDescriptor.setField(DESCRIPTION, description);
            }
            catch (InvocationTargetException e) {
                // ignore
            }
            catch (NoSuchMethodException e) {
                // ignore
            }
            catch (IllegalAccessException e) {
                // ignore
            }
            operationDescriptor.setField(METHOD_INSTANCE, method);

            operations.add(operationDescriptor);

            Matcher matcher = getterOrSetterPattern.matcher(method.getName());
            if (matcher.matches()) {
                String type = matcher.group(1);
                String attributeName = matcher.group(2);

                DescriptorSupport descriptor = attributes.get(attributeName);
                if (descriptor == null) {
                    descriptor = new DescriptorSupport();
                    descriptor.setField("name", attributeName);
                    descriptor.setField("descriptorType", "attribute");
                    descriptor.setField(DESCRIPTION, description);

                    attributes.put(attributeName, descriptor);
                }

                if (type.equals("get") || type.equals("is")) {
                    descriptor.setField(GET_METHOD_INSTANCE, method);
                    descriptor.setField("getMethod", method.getName());
                    operationDescriptor.setField("role", "getter");
                }
                else if (type.equals("set")) {
                    descriptor.setField(SET_METHOD_INSTANCE, method);
                    descriptor.setField("setMethod", method.getName());
                    operationDescriptor.setField("role", "setter");
                }
            }
            else {
                operationDescriptor.setField("role", "operation");
            }
        }

        List<ModelMBeanAttributeInfo> attributeInfos = new ArrayList<ModelMBeanAttributeInfo>();

        for (DescriptorSupport attribute : attributes.values()) {
            Method getter = (Method) attribute.getFieldValue(GET_METHOD_INSTANCE);
            Method setter = (Method) attribute.getFieldValue(SET_METHOD_INSTANCE);
            String description = (String) attribute.getFieldValue(DESCRIPTION);

            // we're piggybacking on Descriptor to hold values that we need here. Remove them before passing
            // them on, as they will cause problems if they are not serializable (e.g., Method)
            strip(attribute);

            attributeInfos.add(new ModelMBeanAttributeInfo(attribute.getFieldValue("name").toString(),
                                                           description,
                                                           getter,
                                                           setter,
                                                           attribute));
        }

        List<ModelMBeanOperationInfo> operationInfos = new ArrayList<ModelMBeanOperationInfo>();

        for (DescriptorSupport operation : operations) {
            String description = (String) operation.getFieldValue(DESCRIPTION);
            Method method = (Method) operation.getFieldValue(METHOD_INSTANCE);

            // we're piggybacking on Descriptor to hold values that we need here. Remove them before passing
            // them on, as they will cause problems if they are not serializable (e.g., Method)
            strip(operation);

            Paranamer paranamer = new BytecodeReadingParanamer();
            String[] names = paranamer.lookupParameterNames(method);
            Class<?>[] types = method.getParameterTypes();

            MBeanParameterInfo[] params = new MBeanParameterInfo[names.length];

            for (int i = 0; i < names.length; ++i) {
                params[i] = new MBeanParameterInfo(names[i], types[i].getName(), null);
            }

            operationInfos.add(new ModelMBeanOperationInfo(method.getName(), description, params,
                                                           method.getReturnType().toString(),
                                                           MBeanOperationInfo.UNKNOWN, operation));
        }

        return new ModelMBeanInfoSupport(clazz.getName(), null,
                                         attributeInfos.toArray(new ModelMBeanAttributeInfo[0]),
                                         new ModelMBeanConstructorInfo[0],
                                         operationInfos.toArray(new ModelMBeanOperationInfo[0]),
                                         new ModelMBeanNotificationInfo[0]);
    }

    private void strip(Descriptor descriptor)
    {
        for (String name : descriptor.getFieldNames()) {
            if (name.startsWith("org.weakref.jmx.")) {
                descriptor.removeField(name);
            }
        }
    }

}
