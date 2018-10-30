package com.xmlcalabash.ext

import java.io.ByteArrayOutputStream
import java.net.URI

import com.jafpl.messages.Message
import com.jafpl.steps.PortCardinality
import com.xmlcalabash.config.DocumentRequest
import com.xmlcalabash.exceptions.XProcException
import com.xmlcalabash.messages.XdmNodeItemMessage
import com.xmlcalabash.model.util.{SaxonTreeBuilder, XProcConstants}
import com.xmlcalabash.runtime.{ExpressionContext, StaticContext, XProcMetadata, XProcXPathExpression, XmlPortSpecification}
import com.xmlcalabash.steps.DefaultXmlStep
import com.xmlcalabash.util.{MediaType, S9Api}
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamSource
import javax.xml.transform.{Source, URIResolver}
import net.sf.saxon.lib.ModuleURIResolver
import net.sf.saxon.om.StructuredQName
import net.sf.saxon.s9api.{Axis, QName, Serializer, XdmAtomicValue, XdmDestination, XdmNode, XdmNodeKind, XdmValue}
import org.xml.sax.InputSource

import scala.collection.mutable

class XSpec() extends DefaultXmlStep {
  private val _untyped = StructuredQName.fromClarkName("{http://www.w3.org/2001/XMLSchema}untyped")
  private val _assert_valid = new QName("", "assert-valid")
  private val _report_format = new QName("", "report-format")
  private val _test_type = new QName("", "test-type")
  private val _coverage = new QName("", "coverage")
  private val _schematron = new QName("", "schematron")
  private val _stylesheet = new QName("", "stylesheet")
  private val xspec = "/etc/xspec-1.1.0"
  private val jt_main = new QName("", "http://www.jenitennison.com/xslt/xspec", "main")
  private val jt_param = new QName("", "http://www.jenitennison.com/xslt/xspec", "param")

  private var sch_compiled: XdmNode = _
  private var cacheStart: String = _
  private var source: XdmNode = _
  private var sourceMetadata: XProcMetadata = _
  private var tests: XdmNode = _
  private var assert_valid = false
  private var report_format = "raw"
  private var test_type = "xslt"
  private var coverage = false

  override def inputSpec: XmlPortSpecification = new XmlPortSpecification(
    Map("source" -> PortCardinality.EXACTLY_ONE,
        "tests" -> PortCardinality.EXACTLY_ONE),
    Map("source" -> List("application/xml", "text/xml", "*/*+xml text/plain"),
        "tests" -> List("application/xml", "text/xml", "*/*+xml")))

  override def outputSpec: XmlPortSpecification = XmlPortSpecification.XMLRESULTSEQ

  override def receive(port: String, item: Any, metadata: XProcMetadata): Unit = {
    item match {
      case node: XdmNode =>
        port match {
          case "source" =>
            source = node
            sourceMetadata = metadata
          case "tests" =>
            tests = node
          case _ => logger.debug(s"Unexpected connection to p:xspec: $port")
        }
      case _ => throw new RuntimeException("Non-XML document passed to XSpec?")
    }
  }

  override def run(context: StaticContext): Unit = {
    super.run(context)

    cacheStart = s"https://xmlcalabash.com/cache/${config.episode}"

    report_format = stringBinding(_report_format).getOrElse("html")
    test_type = stringBinding(_test_type).getOrElse("guess")
    assert_valid = booleanBinding(_assert_valid).getOrElse(false)

    val result = test_type match {
      case "xslt" => runXslt(source, tests)
      case "xquery" => runXQuery()
      case "schematron" =>
        val xspec = runSchematron()
        runXslt(source, xspec)
      case _ =>
        if (sourceMetadata.contentType.textContentType) {
          runXQuery()
        } else {
          runXslt(source, tests)
        }
    }

    if (assert_valid) {
      val expr = new XProcXPathExpression(ExpressionContext.NONE, "//*:test[@successful='false']")
      val bindingsMap = mutable.HashMap.empty[String, Message]
      val nmsg = new XdmNodeItemMessage(result, new XProcMetadata(MediaType.XML))
      val smsg = config.expressionEvaluator.value(expr, List(nmsg), bindingsMap.toMap, None)
      if (smsg.item.size > 0) {
        // FIXME: arrange for a proper exception
        throw XProcException.stepError(999, "At least one XSpec test failed")
      }
    }

    report_format match {
      case "raw" => consumer.get.receive("result", result, new XProcMetadata(MediaType.XML))
      case "html" =>
        val html = transform(result, getXSpecXSLT("reporter/format-xspec-report.xsl"))
        consumer.get.receive("result", html, new XProcMetadata(MediaType.HTML))
      case "junit" =>
        val junit = transform(result, getXSpecXSLT("reporter/junit-report.xsl"))
        consumer.get.receive("result", junit, new XProcMetadata(MediaType.XML))
    }
  }

  private def runXslt(source: XdmNode, tests: XdmNode): XdmNode = {
    val compiled = transform(tests, getXSpecXSLT("compiler/generate-xspec-tests.xsl"))
    transform(source, new SAXSource(S9Api.xdmToInputSource(config.config, compiled)), jt_main)
  }

  private def runXQuery(): XdmNode = {
    val compiled = transform(tests, getXSpecXSLT("compiler/generate-query-tests.xsl"))

    val stream = new ByteArrayOutputStream()
    val serializer = config.processor.newSerializer(stream)
    serializer.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes")
    S9Api.serialize(config.config, compiled, serializer)
    val node = query(source, stream.toString("UTF-8"))
    if (node.getNodeKind == XdmNodeKind.DOCUMENT) {
      node
    } else {
      val builder = new SaxonTreeBuilder(config)
      builder.startDocument(None)
      builder.addSubtree(node)
      builder.endDocument()
      builder.result
    }
  }

  private def runSchematron(): XdmNode = {
    val expr = new XProcXPathExpression(ExpressionContext.NONE, "/*:description")
    val bindingsMap = mutable.HashMap.empty[String, Message]
    val nmsg = new XdmNodeItemMessage(tests, new XProcMetadata(MediaType.XML))
    val smsg = config.expressionEvaluator.singletonValue(expr, List(nmsg), bindingsMap.toMap, None)
    val description = smsg.item.asInstanceOf[XdmNode]
    val sch_uri = description.getBaseURI.resolve(description.getAttributeValue(_schematron))

    val schRequest = new DocumentRequest(sch_uri, MediaType.XML, location)
    val schResponse = config.documentManager.parse(schRequest)
    val sch = schResponse.value.asInstanceOf[XdmNode]

    val params = mutable.HashMap.empty[QName, XdmValue]
    val iter = description.axisIterator(Axis.CHILD)
    while (iter.hasNext) {
      val item = iter.next()
      item match {
        case node: XdmNode =>
          if (node.getNodeName == jt_param) {
            val name = new QName(node.getAttributeValue(XProcConstants._name), node)
            val select = Option(node.getAttributeValue(XProcConstants._select))
            val value = if (select.isDefined) {
              new XdmAtomicValue(node.getAttributeValue(XProcConstants._select))
            } else {
              new XdmAtomicValue(node.getStringValue)
            }
            params.put(name, value)
          }
        case _ => Unit
      }
    }

    val temp1 = transform(sch, getSchematronXSLT("iso_dsdl_include.xsl"))
    val temp2 = transform(temp1, getSchematronXSLT("iso_abstract_expand.xsl"))
    sch_compiled = transform(temp2, getSchematronXSLT("iso_svrl_for_xslt2.xsl"), params.toMap)

    val stylesheet = s"$cacheStart/src/sch_compiled.xsl"
    params.clear()
    params.put(_stylesheet, new XdmAtomicValue(stylesheet))

    val xspec = transform(tests, getXSpecXSLT("schematron/schut-to-xspec.xsl"), params.toMap)

    val tree = new SaxonTreeBuilder(config)
    tree.startDocument(tests.getBaseURI)
    tree.addSubtree(xspec)
    tree.endDocument()

    tree.result
  }

  private def getXSpecXSLT(xslt: String): SAXSource = {
    val resource = s"$xspec/src/$xslt"
    val instream = getClass.getResourceAsStream(resource)
    if (instream == null) {
      throw new RuntimeException(s"Failed to load XSLT: $resource from jar")
    }
    val xresource = s"https://xmlcalabash.com/cache/${config.episode}/jar$resource"
    logger.debug(s"Loaded $resource -> $xresource")
    val source = new InputSource(instream)
    source.setSystemId(xresource)
    new SAXSource(source)
  }

  private def getSchematronXSLT(xslt: String): SAXSource = {
    val instream = getClass.getResourceAsStream(s"/etc/schematron/$xslt")
    if (instream == null) {
      throw new RuntimeException(s"Failed to load Schematron XSLT: /etc/schematron/$xslt from jar")
    }
    new SAXSource(new InputSource(instream))
  }

  private def transform(source: XdmNode, stylesheet: SAXSource): XdmNode = {
    transform(source, stylesheet, None)
  }

  private def transform(source: XdmNode, stylesheet: SAXSource, initialTemplate: QName): XdmNode = {
    transform(source, stylesheet, Some(initialTemplate))
  }

  private def transform(source: XdmNode, stylesheet: SAXSource, initialTemplate: Option[QName]): XdmNode = {
    val compiler = config.processor.newXsltCompiler()
    compiler.setURIResolver(new UResolver())
    val exec = compiler.compile(stylesheet)
    val schemaCompiler = exec.load()

    schemaCompiler.setInitialContextNode(source)
    val result = new XdmDestination()
    schemaCompiler.setDestination(result)
    if (initialTemplate.isDefined) {
      schemaCompiler.setInitialTemplate(initialTemplate.get)
    }
    schemaCompiler.transform()
    val node = result.getXdmNode
    node
  }

  private def transform(source: XdmNode, stylesheet: SAXSource, params: Map[QName, XdmValue]): XdmNode = {
    val compiler = config.processor.newXsltCompiler()
    compiler.setURIResolver(new UResolver())
    val exec = compiler.compile(stylesheet)
    val schemaCompiler = exec.load()

    for ((param, value) <- params) {
      schemaCompiler.setParameter(param, value)
    }

    schemaCompiler.setInitialContextNode(source)
    val result = new XdmDestination()
    schemaCompiler.setDestination(result)
    schemaCompiler.transform()
    val node = result.getXdmNode
    node
  }

  private def query(source: XdmNode, query: String): XdmNode = {
    val compiler = config.processor.newXQueryCompiler()
    val modres = compiler.getModuleURIResolver
    compiler.setModuleURIResolver(new UResolver(modres))
    val exec = compiler.compile(query)
    val eval = exec.load()

    var node = Option.empty[XdmNode]
    val iter = eval.iterator()
    while (iter.hasNext) {
      val item = iter.next()
      item match {
        case n: XdmNode =>
          node = Some(n)
      }
    }

    node.get
  }

  private class UResolver extends URIResolver with ModuleURIResolver {
    var _chainedModuleResolver: ModuleURIResolver = _

    def this(modres: ModuleURIResolver) {
      this()
      _chainedModuleResolver = modres
    }

    override def resolve(href: String, base: String): Source = {
      val absURI = new URI(base).resolve(href).toASCIIString

      val uri = href match {
        case "generate-common-tests.xsl" => s"$xspec/src/compiler/$href"
        case "generate-query-helper.xsl" => s"$xspec/src/compiler/$href"
        case "generate-query-tests.xsl" => s"$xspec/src/compiler/$href"
        case "generate-query-utils.xql" => s"$xspec/src/compiler/$href"
        case "generate-tests-helper.xsl" => s"$xspec/src/compiler/$href"
        case "generate-tests-utils.xsl" => s"$xspec/src/compiler/$href"
        case "generate-xspec-tests.xsl" => s"$xspec/src/compiler/$href"
        case "coverage-report.xsl" => s"$xspec/src/reporter/$href"
        case "format-utils.xsl" => s"$xspec/src/reporter/$href"
        case "format-xspec-report-folding.xsl" => s"$xspec/src/reporter/$href"
        case "format-xspec-report.xsl" => s"$xspec/src/reporter/$href"
        case "junit-report.xsl" => s"$xspec/src/reporter/$href"
        case "test-report.css" => s"$xspec/src/reporter/$href"
        case "sch-location-compare.xsl" => s"$xspec/src/schematron/$href"
        case "schut-to-xspec.xsl" => s"$xspec/src/schematron/$href"
        case "iso_schematron_skeleton_for_saxon.xsl" => "/etc/schematron/iso_schematron_skeleton_for_saxon.xsl"
        case _ =>
          val jarStart = s"$cacheStart/jar"
          if (absURI.startsWith(jarStart)) {
            absURI.substring(jarStart.length)
          } else {
            ""
          }
      }

      if (uri == "") {
        if (absURI == s"$cacheStart/src/sch_compiled.xsl") {
          val source = S9Api.xdmToInputSource(config.config, sch_compiled)
          source.setSystemId(href)
          new SAXSource(source)
        } else {
          val req = new DocumentRequest(new URI(href), MediaType.XML)
          req.baseURI = new URI(base)
          val resp = config.documentManager.parse(req)
          val source = S9Api.xdmToInputSource(config.config, resp.value.asInstanceOf[XdmNode])
          source.setSystemId(href)
          new SAXSource(source)
        }
      } else {
        val instream = getClass.getResourceAsStream(uri)
        if (instream == null) {
          throw new RuntimeException(s"Failed to load resource: $uri from jar")
        }
        val xresource = s"https://xmlcalabash.com/cache/${config.episode}/jar$uri"
        logger.debug(s"Loaded $uri -> $xresource")
        val source = new InputSource(instream)
        source.setSystemId(xresource)
        new SAXSource(source)
      }
    }

    override def resolve(href: String, base: String, locations: Array[String]): Array[StreamSource] = {
      for (location <- locations) {
        val absURI = if (base != null) {
          new URI(base).resolve(location).toASCIIString
        } else {
          location
        }

        if (absURI.startsWith(cacheStart)) {
          val jarStart = s"$cacheStart/jar"
          val jaruri = absURI.substring(jarStart.length)
          val instream = getClass.getResourceAsStream(jaruri)
          if (instream == null) {
            throw new RuntimeException(s"Failed to load resource: $location from jar")
          }
          val source = new StreamSource(instream)
          source.setSystemId(absURI)
          return Array(source)
        }
      }

      if (_chainedModuleResolver != null) {
        _chainedModuleResolver.resolve(href, base, locations)
      } else {
        null
      }
   }
  }
}
