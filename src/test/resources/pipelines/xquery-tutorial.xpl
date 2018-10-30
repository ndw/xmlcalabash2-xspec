<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:cx="http://xmlcalabash.com/ns/extensions"
                version="3.0">
  <p:output port="result"/>
  <p:option name="report-format" select="'raw'"/>
  <p:option name="test-type" select="'xquery'"/>
  <p:option name="source-uri" required="true"/>
  <p:option name="xspec-uri" required="true"/>
  <p:option name="assert-valid" select="false()" as="xs:boolean"/>

  <p:declare-step type="cx:xspec">
    <p:input port="source" primary="true" content-types="application/xml text/xml */*+xml text/plain"/>
    <p:input port="tests" content-types="application/xml text/xml */*+xml"/>
    <p:output port="result" sequence="true" content-types="application/xml"/>
    <p:option name="assert-valid" select="false()" as="xs:boolean"/>
    <p:option name="parameters" as="map(xs:QName,item())"/>
    <p:option name="report-format" select="'raw'" cx:as="raw|html|junit"/>
    <p:option name="test-type" cx:as="xslt|xquery|schematron"/>
    <p:option name="coverage" select="false()" as="xs:boolean"/>
  </p:declare-step>

  <cx:xspec report-format="{$report-format}" test-type="{$test-type}" assert-valid="{$assert-valid}">
    <p:with-input port="source">
      <p:document href="{$source-uri}" content-type="text/plain"/>
    </p:with-input>
    <p:with-input port="tests">
      <p:document href="{$xspec-uri}"/>
    </p:with-input>
  </cx:xspec>

</p:declare-step>
