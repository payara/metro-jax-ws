/**
 * $Id: SeiGenerator.java,v 1.34 2005-10-14 21:58:23 vivekp Exp $
 */

/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the "License").  You may not use this file except
 * in compliance with the License.
 * 
 * You can obtain a copy of the license at
 * https://jwsdp.dev.java.net/CDDLv1.0.html
 * See the License for the specific language governing
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL
 * HEADER in each file and include the License file at
 * https://jwsdp.dev.java.net/CDDLv1.0.html  If applicable,
 * add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your
 * own identifying information: Portions Copyright [yyyy]
 * [name of copyright owner]
 */

package com.sun.tools.ws.processor.generator;

import com.sun.codemodel.*;
import com.sun.codemodel.writer.ProgressCodeWriter;
import com.sun.tools.ws.processor.ProcessorAction;
import com.sun.tools.ws.processor.config.Configuration;
import com.sun.tools.ws.processor.config.WSDLModelInfo;
import com.sun.tools.ws.processor.model.*;
import com.sun.tools.ws.processor.model.java.JavaInterface;
import com.sun.tools.ws.processor.model.java.JavaMethod;
import com.sun.tools.ws.processor.model.java.JavaParameter;
import com.sun.tools.ws.processor.model.jaxb.JAXBType;
import com.sun.tools.ws.processor.model.jaxb.JAXBTypeAndAnnotation;
import com.sun.tools.ws.processor.util.DirectoryUtil;
import com.sun.tools.ws.processor.util.GeneratedFileInfo;
import com.sun.tools.ws.processor.util.IndentingWriter;
import com.sun.tools.ws.wscompile.WSCodeWriter;
import com.sun.tools.ws.wsdl.document.soap.SOAPStyle;
import com.sun.tools.ws.wsdl.document.PortType;
import com.sun.tools.xjc.api.TypeAndAnnotation;
import com.sun.xml.ws.encoding.soap.SOAPVersion;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.jws.HandlerChain;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.ws.Holder;
import javax.xml.namespace.QName;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Properties;

public class SeiGenerator extends GeneratorBase implements ProcessorAction {
    private WSDLModelInfo wsdlModelInfo;
    private String serviceNS;
    
    // empty string per section 9.2.1.3
    private static final String HANDLER_CHAIN_NAME = "";
    
    public SeiGenerator() {
    }

    protected void doGeneration() {
        try {
            model.accept(this);
        } catch (Exception e) {
            if (env.verbose())
                e.printStackTrace();
            throw new GeneratorException(
                "generator.nestedGeneratorError",
                e);
        }
    }

    public GeneratorBase getGenerator(Model model, Configuration config, Properties properties) {
        return new SeiGenerator(model, config, properties);
    }

    public SeiGenerator(Model model, Configuration config, Properties properties) {
        super(model, config, properties);
        this.model = model;
        this.wsdlModelInfo = (WSDLModelInfo)config.getModelInfo();
    }

    public GeneratorBase getGenerator(Model model, Configuration config, Properties properties, SOAPVersion ver) {
        return new SeiGenerator(model, config, properties);
    }


    private void write(Service service, Port port) throws Exception{
        JavaInterface intf = port.getJavaInterface();
        String className = env.getNames().customJavaTypeClassName(intf);

        if (donotOverride && GeneratorUtil.classExists(env, className)) {
            log("Class " + className + " exists. Not overriding.");
            return;
        }
        
        
        JDefinedClass cls = getClass(className, ClassType.INTERFACE);
        if (cls == null)
            return;
        
        // If the class has methods it has already been defined
        // so skip it.
        if (!cls.methods().isEmpty())
            return;

        //write class comment - JAXWS warning
        JDocComment comment = cls.javadoc();

        String ptDoc = intf.getJavaDoc();
        if(ptDoc != null){
            comment.add(ptDoc);
            comment.add("\n\n");
        }

        for(String doc:getJAXWSClassComment()){
                comment.add(doc);
        }


        //@WebService
        JAnnotationUse webServiceAnn = cls.annotate(cm.ref(WebService.class));
        writeWebServiceAnnotation(service, port, webServiceAnn);

        //@HandlerChain
        writeHandlerConfig(port, cls);

        //@SOAPBinding
        writeSOAPBinding(port, cls);

        for (Operation operation: port.getOperations()) {
            JavaMethod method = operation.getJavaMethod();

            //@WebMethod
            JMethod m = null;
            JDocComment methodDoc = null;
            String methodJavaDoc = operation.getJavaDoc();
            if(method.getReturnType().getName().equals("void")){
                m = cls.method(JMod.PUBLIC, void.class, method.getName());
                methodDoc = m.javadoc();
            }else {
                JAXBTypeAndAnnotation retType = method.getReturnType().getType();
                m = cls.method(JMod.PUBLIC, retType.getType(), method.getName());
                retType.annotate(m);
                methodDoc = m.javadoc();
                JCommentPart ret = methodDoc.addReturn();
                ret.add("returns "+retType.getName());
            }
            if(methodJavaDoc != null)
                methodDoc.add(methodJavaDoc);

            writeWebMethod(operation, m);
            JClass holder = cm.ref(Holder.class);
            for (JavaParameter parameter: method.getParametersList()) {
                JVar var = null;
                JAXBTypeAndAnnotation paramType = parameter.getType().getType();
                if (parameter.isHolder()) {
                    var = m.param(holder.narrow(paramType.getType().boxify()), parameter.getName());
                }else{
                    var = m.param(paramType.getType(), parameter.getName());
                }

                //annotate parameter with JAXB annotations
                paramType.annotate(var);
                methodDoc.addParam(var);
                JAnnotationUse paramAnn = var.annotate(cm.ref(WebParam.class));
                writeWebParam(operation, parameter, paramAnn);
            }
            for(Fault fault:operation.getFaultsSet()){
                m._throws(fault.getExceptionClass());
                methodDoc.addThrows(fault.getExceptionClass());
            }
        }
        CodeWriter cw = new WSCodeWriter(sourceDir,env);

        if(env.verbose())
            cw = new ProgressCodeWriter(cw, System.out);
        cm.build(cw);
    }

    private void writeWebMethod(Operation operation, JMethod m) {
        Response response = operation.getResponse();
        JAnnotationUse webMethodAnn = m.annotate(cm.ref(WebMethod.class));;
        String operationName = (operation instanceof AsyncOperation)?
                ((AsyncOperation)operation).getNormalOperation().getName().getLocalPart():
                operation.getName().getLocalPart();

        if(!m.name().equals(operationName)){
            webMethodAnn.param("operationName", operationName);
        }

        if (operation.getSOAPAction() != null && operation.getSOAPAction().length() > 0){
            webMethodAnn.param("action", operation.getSOAPAction());
        }

        if (operation.getResponse() == null){
            m.annotate(javax.jws.Oneway.class);
        }else if (!operation.getJavaMethod().getReturnType().getName().equals("void") &&
                 operation.getResponse().getParametersList().size() > 0){
            Block block = null;
            String resultName = null;
            String nsURI = null;
            if (operation.getResponse().getBodyBlocks().hasNext()) {
                block = operation.getResponse().getBodyBlocks().next();
                resultName = block.getName().getLocalPart();
                nsURI = block.getName().getNamespaceURI();                
            }

            for (Parameter parameter : operation.getResponse().getParametersList()) {
                if (parameter.getParameterIndex() == -1) {
                    if(operation.isWrapped()||!isDocStyle){
                        if(parameter.getBlock().getLocation() == Block.HEADER){
                            resultName = parameter.getBlock().getName().getLocalPart(); 
                        }else{
                            resultName = parameter.getName();
                        }
                        if (isDocStyle || (parameter.getBlock().getLocation() == Block.HEADER)) {
                            nsURI = parameter.getType().getName().getNamespaceURI();
                        }
                    }else if(isDocStyle){
                        JAXBType t = (JAXBType)parameter.getType();
                        resultName = t.getName().getLocalPart();
                        nsURI = t.getName().getNamespaceURI();
                    }
                    if(!(operation instanceof AsyncOperation)){
                        JAnnotationUse wr = null;

                        if(!resultName.equals("return")){
                            if(wr == null)
                                wr = m.annotate(javax.jws.WebResult.class);
                            wr.param("name", resultName);
                        }
                        //if (operation.getStyle().equals(SOAPStyle.DOCUMENT) && !(nsURI.equals(serviceNS))) {
                        if((nsURI != null) && (!nsURI.equals(serviceNS) || (isDocStyle && operation.isWrapped()))){
                            if(wr == null)
                                wr = m.annotate(javax.jws.WebResult.class);
                            wr.param("targetNamespace", nsURI);
                        }
                        //doclit wrapped could have additional headers
                        if(!(isDocStyle && operation.isWrapped()) ||
                                (parameter.getBlock().getLocation() == Block.HEADER)){
                            if(wr == null)
                                wr = m.annotate(javax.jws.WebResult.class);
                            wr.param("partName", parameter.getName());
                        }
                        if(parameter.getBlock().getLocation() == Block.HEADER){
                            if(wr == null)
                                wr = m.annotate(javax.jws.WebResult.class);
                            wr.param("header",true);
                        }
                    }
                }

            }
        }

        //DOC/BARE
        if (!sameParamStyle) {
            if(!operation.isWrapped()) {
               JAnnotationUse sb = m.annotate(SOAPBinding.class);
               sb.param("parameterStyle", SOAPBinding.ParameterStyle.BARE);
            }
        }

        if (operation.isWrapped() && operation.getStyle().equals(SOAPStyle.DOCUMENT)) {
            Block reqBlock = operation.getRequest().getBodyBlocks().next();
            JAnnotationUse reqW = m.annotate(javax.xml.ws.RequestWrapper.class);
            reqW.param("localName", reqBlock.getName().getLocalPart());
            reqW.param("targetNamespace", reqBlock.getName().getNamespaceURI());
            reqW.param("className", reqBlock.getType().getJavaType().getName());

            if (response != null) {
                JAnnotationUse resW = m.annotate(javax.xml.ws.ResponseWrapper.class);
                Block resBlock = response.getBodyBlocks().next();
                resW.param("localName", resBlock.getName().getLocalPart());
                resW.param("targetNamespace", resBlock.getName().getNamespaceURI());
                resW.param("className", resBlock.getType().getJavaType().getName());
            }
        }
    }

    //TODO: JAXB should expose the annotations so that it can be added to JAnnotationUse
    protected void writeJAXBTypeAnnotations(JAnnotationUse annUse, Parameter param) throws IOException{
        List<String> annotations = param.getAnnotations();
        if(annotations == null)
            return;

        for(String annotation:param.getAnnotations()){
            //p.pln(annotation);
            //annUse.
        }
    }

    private boolean isMessageParam(Parameter param, Message message) {
        Block block = param.getBlock();

        return (message.getBodyBlockCount() > 0 && block.equals(message.getBodyBlocks().next())) ||
               (message.getHeaderBlockCount() > 0 &&
               block.equals(message.getHeaderBlocks().next()));
    }    

    private boolean isHeaderParam(Parameter param, Message message) {
        if (message.getHeaderBlockCount() == 0)
            return false;

        for (Block headerBlock : message.getHeaderBlocksMap().values())
            if (param.getBlock().equals(headerBlock))
                return true;

        return false;
    }

    private boolean isAttachmentParam(Parameter param, Message message){
        if (message.getAttachmentBlockCount() == 0)
            return false;

        for (Block attBlock : message.getAttachmentBlocksMap().values())
            if (param.getBlock().equals(attBlock))
                return true;

        return false;
    }

    private boolean isUnboundParam(Parameter param, Message message){
        if (message.getUnboundBlocksCount() == 0)
            return false;

        for (Block unboundBlock : message.getUnboundBlocksMap().values())
            if (param.getBlock().equals(unboundBlock))
                return true;

        return false;
    }

    private void writeWebParam(Operation operation, JavaParameter javaParameter, JAnnotationUse paramAnno) {
        Parameter param = javaParameter.getParameter();
        Request req = operation.getRequest();
        Response res = operation.getResponse();

        boolean header = isHeaderParam(param, req) ||
            (res != null ? isHeaderParam(param, res) : false);

        String name;
        boolean isWrapped = operation.isWrapped();

        if((param.getBlock().getLocation() == Block.HEADER) || (isDocStyle && !isWrapped))
            name = param.getBlock().getName().getLocalPart();
        else
            name = param.getName();

        paramAnno.param("name", name);

        String ns= null;

        if (isDocStyle) {
            ns = param.getBlock().getName().getNamespaceURI(); // its bare nsuri
            if(isWrapped){
                ns = ((JAXBType)param.getType()).getName().getNamespaceURI();
            }
        }else if(!isDocStyle && header){
            ns = param.getBlock().getName().getNamespaceURI();
        }

        if((ns != null) && (!ns.equals(serviceNS) || (isDocStyle && isWrapped)))
            paramAnno.param("targetNamespace", ns);

        if (header) {
            paramAnno.param("header", true);
        }

        if (param.isINOUT()){
            paramAnno.param("mode", javax.jws.WebParam.Mode.INOUT);
        }else if ((res != null) && (isMessageParam(param, res) || isHeaderParam(param, res) || isAttachmentParam(param, res) ||
                isUnboundParam(param,res))){
            paramAnno.param("mode", javax.jws.WebParam.Mode.OUT);
        }

        //doclit wrapped could have additional headers
        if(!(isDocStyle && isWrapped) || header)
            paramAnno.param("partName", javaParameter.getParameter().getName());
    }

    boolean isDocStyle = true;
    boolean sameParamStyle = true;
    private void writeSOAPBinding(Port port, JDefinedClass cls) {
        JAnnotationUse soapBindingAnn = null;
        isDocStyle = port.getStyle() != null ? port.getStyle().equals(SOAPStyle.DOCUMENT) : true;
        if(!isDocStyle){
            if(soapBindingAnn == null)
                soapBindingAnn = cls.annotate(SOAPBinding.class);
            soapBindingAnn.param("style", SOAPBinding.Style.RPC);
            port.setWrapped(true);
        }
        if(isDocStyle){
            boolean first = true;
            boolean isWrapper = true;
            for(Operation operation:port.getOperations()){
                if(first){
                    isWrapper = operation.isWrapped();
                    first = false;
                    continue;
                }
                sameParamStyle = (isWrapper == operation.isWrapped());
                if(!sameParamStyle)
                    break;
            }
            if(sameParamStyle)
                port.setWrapped(isWrapper);
        }
        if(sameParamStyle && !port.isWrapped()){
            if(soapBindingAnn == null)
                soapBindingAnn = cls.annotate(SOAPBinding.class);
            soapBindingAnn.param("parameterStyle", SOAPBinding.ParameterStyle.BARE);
        }
    }

    private void writeHandlerConfig(Port port, JDefinedClass cls) {
        Element e = wsdlModelInfo.getHandlerConfig();
        if(e == null)
            return;
        JAnnotationUse handlerChainAnn = cls.annotate(cm.ref(HandlerChain.class));
        String fullName = env.getNames().customJavaTypeClassName(port.getJavaInterface());
        NodeList nl = e.getElementsByTagNameNS(
            "http://java.sun.com/xml/ns/javaee", "handler-chain");
        if(nl.getLength() > 0){
            Element hn = (Element)nl.item(0);
            String fName = getHandlerConfigFileName(fullName);
            handlerChainAnn.param("name", HANDLER_CHAIN_NAME);
            handlerChainAnn.param("file", fName);
            generateHandlerChainFile(e, fullName);
        }
    }

     private String getHandlerConfigFileName(String fullName){
        String name = Names.stripQualifier(fullName);
        return name+"_handler.xml";
    }

    private void writeWebServiceAnnotation(Service service, Port port, JAnnotationUse wsa) {
        String serviceName = service.getName().getLocalPart();
        QName name = (QName) port.getProperty(ModelProperties.PROPERTY_WSDL_PORT_TYPE_NAME);
//        serviceNS = service.getName().getNamespaceURI();
//        wsa.param("serviceName", serviceName);
//        wsa.param("targetNamespace", serviceNS);
        wsa.param("name", name.getLocalPart());
        wsa.param("targetNamespace", name.getNamespaceURI());
        wsa.param("wsdlLocation", wsdlLocation);
    }




    public void visit(Model model) throws Exception {
        for(Service s:model.getServices()){
            s.accept(this);
        }
    }

    public void visit(Service service) throws Exception {
        for(Port p:service.getPorts()){
            visitPort(service, p);
        }
    }

    private void visitPort(Service service, Port port) {
        if (port.isProvider()) {
            return;                // Not generating for Provider based endpoint
        }


        try {
            write(service, port);
        } catch (Exception e) {
            throw new GeneratorException(
                "generator.nestedGeneratorError",
                e);
        }
    }

    private void generateHandlerChainFile(Element hChains, String name) {
        String hcName = getHandlerConfigFileName(name);

        File packageDir = DirectoryUtil.getOutputDirectoryFor(name, destDir, env);
        File hcFile = new File(packageDir, hcName);

        /* adding the file name and its type */
        GeneratedFileInfo fi = new GeneratedFileInfo();
        fi.setFile(hcFile);
        fi.setType("HandlerConfig");
        env.addGeneratedFile(fi);

        try {
            IndentingWriter p =
                new IndentingWriter(
                    new OutputStreamWriter(new FileOutputStream(hcFile)));
            Transformer it = TransformerFactory.newInstance().newTransformer();

            it.setOutputProperty(OutputKeys.METHOD, "xml");
            it.setOutputProperty(OutputKeys.INDENT, "yes");
            it.setOutputProperty(
                "{http://xml.apache.org/xslt}indent-amount",
                "2");
            it.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            it.transform( new DOMSource(hChains), new StreamResult(p) );
        } catch (Exception e) {
            throw new GeneratorException(
                    "generator.nestedGeneratorError",
                    e);
        }
    }
}
