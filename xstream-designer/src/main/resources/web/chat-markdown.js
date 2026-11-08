'use strict';
// Render public assistant text only. Raw HTML, remote images and unsafe links are disabled.
window.ChatMarkdown=(()=>{
 const parser=window.markdownit({html:false,linkify:false,breaks:true,typographer:false}).disable('image');
 const link=parser.renderer.rules.link_open||((tokens,index,options,env,self)=>self.renderToken(tokens,index,options));
 parser.renderer.rules.link_open=(tokens,index,options,env,self)=>{tokens[index].attrSet('target','_blank');tokens[index].attrSet('rel','noopener noreferrer');return link(tokens,index,options,env,self);};
 return{render(node,text){node.innerHTML=parser.render(String(text||''));}};
})();
