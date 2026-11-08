package xstream.designer

/** Incrementally decodes only the public top-level reply string, never reasoning fields or Job YAML. */
private[designer] final class ChatReplyStream(emit:String=>Unit) {
 private var depth=0
 private var inString=false
 private var expectKey=false
 private var collectingKey=false
 private var readingReply=false
 private var escaped=false
 private var unicode:String=null
 private var pendingHigh:Option[Char]=None
 private var key=""
 private val keyText=new StringBuilder
 private val delta=new StringBuilder
 private def decoded(c:Char):Unit={
  if(collectingKey)keyText.append(c)
  if(readingReply){
   if(Character.isHighSurrogate(c))pendingHigh=Some(c)
   else if(Character.isLowSurrogate(c)){pendingHigh.foreach(delta.append);pendingHigh=None;delta.append(c)}
   else{pendingHigh=None;delta.append(c)}
  }
 }
 def feed(chunk:String):Unit={
  chunk.foreach{c=>
   if(inString){
    if(unicode!=null){
     unicode+=c
     if(unicode.length==4){decoded(Integer.parseInt(unicode,16).toChar);unicode=null}
    }else if(escaped){
     escaped=false
     c match {
      case 'u'=>unicode=""
      case 'n'=>decoded('\n')
      case 'r'=>decoded('\r')
      case 't'=>decoded('\t')
      case 'b'=>decoded('\b')
      case 'f'=>decoded('\f')
      case other=>decoded(other)
     }
    }else c match {
     case '\\'=>escaped=true
     case '"'=>inString=false;if(collectingKey)key=keyText.toString;collectingKey=false;readingReply=false
     case other=>decoded(other)
    }
   }else c match {
    case '{'|'['=>depth+=1;if(depth==1)expectKey=true
    case '}'|']'=>depth-=1
    case ':' if depth==1=>expectKey=false
    case ',' if depth==1=>expectKey=true
    case '"'=>inString=true;collectingKey=depth==1&&expectKey;readingReply=depth==1 && !expectKey && key=="reply";keyText.clear()
    case _=>()
   }
  }
  if(delta.nonEmpty){emit(delta.toString);delta.clear()}
 }
}
