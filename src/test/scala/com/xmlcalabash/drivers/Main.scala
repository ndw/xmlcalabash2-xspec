package com.xmlcalabash.drivers

import java.net.URI

import com.xmlcalabash.config.XMLCalabashConfig
import com.xmlcalabash.runtime.{PrintingConsumer, XMLCalabashRuntime}
import net.sf.saxon.s9api.QName

object Main extends App {
  val pipeline = new URI(args.head)
  val document = args(1)
  val xspec = args(2)

  val config = XMLCalabashConfig.newInstance()

  val runtime: XMLCalabashRuntime = config.runtime(pipeline)

  runtime.option(new QName("", "document"), document)

  val serOpt = runtime.serializationOptions("result")
  val pc = new PrintingConsumer(runtime, serOpt)
  runtime.output("result", pc)

  runtime.run()
}
