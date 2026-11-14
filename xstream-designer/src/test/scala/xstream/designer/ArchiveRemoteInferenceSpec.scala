package xstream.designer

import xstream.data._
import xstream.util.Json
import xstream.connector.dacp.protocol.Metadata
import org.apache.arrow.flight._
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{VectorSchemaRoot,VarBinaryVector}
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import java.net.URI
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import scala.sys.process._

class ArchiveRemoteInferenceSpec extends AnyFunSuite {
  test("remote and target-context archive inference select bytes before testing kind and export YAML") {
    val dir=Files.createTempDirectory("designer-remote-zip-");val allocator=new RootAllocator()
    val cert=dir.resolve("cert.pem");val key=dir.resolve("key.pem")
    require(Process(Seq("openssl","req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=localhost","-addext","subjectAltName=DNS:localhost","-keyout",key.toString,"-out",cert.toString)).!(ProcessLogger(_=>(),_=>()))==0)
    val bytes=Array[Byte](80,75,3,4,0,1,2,3)
    val producer=new NoOpFlightProducer {
      override def getFlightInfo(c:FlightProducer.CallContext,d:FlightDescriptor):FlightInfo={
        val path=d.getPath.get(d.getPath.size-1)
        val folder=path.endsWith(".zip") || path.endsWith("/labels")
        val meta=Metadata.Item.newBuilder().setPath(path).setKind(if(folder)"folder"else "data").setFormat(if(path.endsWith(".zip"))"zip"else "bin")
        if(!path.endsWith("/labels"))meta.addViews("bytes")
        val ticket=new Ticket(Metadata.Ticket.newBuilder().setDataset("files").setItem(path).setView("bytes").build().toByteArray)
        new FlightInfo(PluginSchemas.bytes,d,java.util.List.of(new FlightEndpoint(ticket)), -1L,-1L,false,org.apache.arrow.vector.ipc.message.IpcOption.DEFAULT,meta.build().toByteArray)
      }
      override def getStream(c:FlightProducer.CallContext,t:Ticket,l:FlightProducer.ServerStreamListener):Unit={
        val request=Metadata.Ticket.parseFrom(t.getBytes);assert(request.getView=="bytes")
        val root=VectorSchemaRoot.create(PluginSchemas.bytes,allocator)
        try {root.allocateNew();root.getVector(0).asInstanceOf[VarBinaryVector].setSafe(0,bytes);root.setRowCount(1);l.start(root);l.putNext();l.completed()}finally root.close()
      }
    }
    val server=FlightServer.builder(allocator,Location.forGrpcTls("localhost",0),producer).useTls(cert.toFile,key.toFile).build().start()
    val origin=s"dacp://localhost:${server.getPort}"
    val config=Documents.mapping(s"host: 127.0.0.1\nport: 0\nremote_operator_library_urls: ['http://127.0.0.1:1']\nnodes:\n- {url: '$origin', ca: '$cert'}\n")
    val site=new Designer(config);site.start();val client=HttpClient.newHttpClient()
    def post(path:String,body:com.fasterxml.jackson.databind.JsonNode)=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString)).build(),HttpResponse.BodyHandlers.ofString())
    try {
      for(prefix<-Seq(origin+"/files/","context:/files/");(path,view,ok)<-Seq(("plain.txt","bytes",true),("geotrans-input.zip","bytes",true),("geotrans-input.zip/labels/points.txt","bytes",true),("geotrans-input.zip/labels","bytes",false),("geotrans-input.zip","records",false))) {
        val yaml=s"name: Archive\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: '$prefix$path', view: $view}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: read}\n"
        val request=Json.obj().put("target",origin).put("job_yaml",yaml).put("variables_yaml","{}")
        if(ok)request.set[com.fasterxml.jackson.databind.JsonNode]("preview",Json.obj().put("max_rows",1).put("max_bytes",1024))
        val response=post("/api/infer",request)
        val output=Json.mapper.readTree(response.body()).path("steps").path("read").path("outputs").path("result")
        assert((output.path("certainty").asText=="known")==ok,response.body())
        if(ok){assert(response.statusCode()==200,response.body());assert(post("/api/export",request.put("include_designer",false)).statusCode()==200)}
      }
    }finally {site.close();server.close();allocator.close();Data.deleteTree(dir)}
  }
}
