/*! For license information please see 938.ace0267c.js.LICENSE.txt */
(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["938"],{98950:function(e,t,l){"use strict";l.r(t),l.d(t,{diagram:function(){return g}});var a=l("14892"),i=l("11049"),n=l("50043"),o=l("76187"),s=l("38739");l("27484"),l("17967"),l("27856"),l("374"),l("2024");let d=e=>o.e.sanitizeText(e,(0,o.c)()),r={dividerMargin:10,padding:5,textHeight:10,curve:void 0},c=function(e,t,l,a){let i=Object.keys(e);o.l.info("keys:",i),o.l.info(e),i.forEach(function(i){var n,s;let r=e[i],c={shape:"rect",id:r.id,domId:r.domId,labelText:d(r.id),labelStyle:"",style:"fill: none; stroke: black",padding:(null==(n=(0,o.c)().flowchart)?void 0:n.padding)??(null==(s=(0,o.c)().class)?void 0:s.padding)};t.setNode(r.id,c),p(r.classes,t,l,a,r.id),o.l.info("setNode",c)})},p=function(e,t,l,a,i){let n=Object.keys(e);o.l.info("keys:",n),o.l.info(e),n.filter(t=>e[t].parent==i).forEach(function(l){var n,s;let r=e[l],c=r.cssClasses.join(" "),p=(0,o.k)(r.styles),b=r.label??r.id,f={labelStyle:p.labelStyle,shape:"class_box",labelText:d(b),classData:r,rx:0,ry:0,class:c,style:p.style,id:r.id,domId:r.domId,tooltip:a.db.getTooltip(r.id,i)||"",haveCallback:r.haveCallback,link:r.link,width:"group"===r.type?500:void 0,type:r.type,padding:(null==(n=(0,o.c)().flowchart)?void 0:n.padding)??(null==(s=(0,o.c)().class)?void 0:s.padding)};t.setNode(r.id,f),i&&t.setParent(r.id,i),o.l.info("setNode",f)})},b=function(e,t,l,a){o.l.info(e),e.forEach(function(e,n){var s,c;let p="",b="",f=e.text,u={labelStyle:p,shape:"note",labelText:d(f),noteData:e,rx:0,ry:0,class:"",style:b,id:e.id,domId:e.id,tooltip:"",type:"note",padding:(null==(s=(0,o.c)().flowchart)?void 0:s.padding)??(null==(c=(0,o.c)().class)?void 0:c.padding)};if(t.setNode(e.id,u),o.l.info("setNode",u),!e.class||!(e.class in a))return;let y=l+n,g={id:`edgeNote${y}`,classes:"relation",pattern:"dotted",arrowhead:"none",startLabelRight:"",endLabelLeft:"",arrowTypeStart:"none",arrowTypeEnd:"none",style:"fill:none",labelStyle:"",curve:(0,o.n)(r.curve,i.c_6)};t.setEdge(e.id,e.class,g,y)})},f=function(e,t){let l=(0,o.c)().flowchart,a=0;e.forEach(function(e){var n;a++;let s={classes:"relation",pattern:1==e.relation.lineType?"dashed":"solid",id:`id_${e.id1}_${e.id2}_${a}`,arrowhead:"arrow_open"===e.type?"none":"normal",startLabelRight:"none"===e.relationTitle1?"":e.relationTitle1,endLabelLeft:"none"===e.relationTitle2?"":e.relationTitle2,arrowTypeStart:y(e.relation.type1),arrowTypeEnd:y(e.relation.type2),style:"fill:none",labelStyle:"",curve:(0,o.n)(null==l?void 0:l.curve,i.c_6)};if(o.l.info(s,e),void 0!==e.style){let t=(0,o.k)(e.style);s.style=t.style,s.labelStyle=t.labelStyle}e.text=e.title,void 0===e.text?void 0!==e.style&&(s.arrowheadStyle="fill: #333"):(s.arrowheadStyle="fill: #333",s.labelpos="c",(null==(n=(0,o.c)().flowchart)?void 0:n.htmlLabels)??(0,o.c)().htmlLabels?(s.labelType="html",s.label='<span class="edgeLabel">'+e.text+"</span>"):(s.labelType="text",s.label=e.text.replace(o.e.lineBreakRegex,"\n"),void 0===e.style&&(s.style=s.style||"stroke: #333; stroke-width: 1.5px;fill:none"),s.labelStyle=s.labelStyle.replace("color:","fill:"))),t.setEdge(e.id1,e.id2,s,a)})},u=async function(e,t,l,a){let d;o.l.info("Drawing class - ",t);let r=(0,o.c)().flowchart??(0,o.c)().class,u=(0,o.c)().securityLevel;o.l.info("config:",r);let y=(null==r?void 0:r.nodeSpacing)??50,g=(null==r?void 0:r.rankSpacing)??50,h=new n.k({multigraph:!0,compound:!0}).setGraph({rankdir:a.db.getDirection(),nodesep:y,ranksep:g,marginx:8,marginy:8}).setDefaultEdgeLabel(function(){return{}}),v=a.db.getNamespaces(),w=a.db.getClasses(),k=a.db.getRelations(),m=a.db.getNotes();o.l.info(k),c(v,h,t,a),p(w,h,t,a),f(k,h),b(m,h,k.length+1,w),"sandbox"===u&&(d=(0,i.Ys)("#i"+t));let x="sandbox"===u?(0,i.Ys)(d.nodes()[0].contentDocument.body):(0,i.Ys)("body"),T=x.select(`[id="${t}"]`),S=x.select("#"+t+" g");if(await (0,s.r)(S,h,["aggregation","extension","composition","dependency","lollipop"],"classDiagram",t),o.u.insertTitle(T,"classTitleText",(null==r?void 0:r.titleTopMargin)??5,a.db.getDiagramTitle()),(0,o.o)(h,T,null==r?void 0:r.diagramPadding,null==r?void 0:r.useMaxWidth),!(null==r?void 0:r.htmlLabels)){let e="sandbox"===u?d.nodes()[0].contentDocument:document;for(let l of e.querySelectorAll('[id="'+t+'"] .edgeLabel .label')){let t=l.getBBox(),a=e.createElementNS("http://www.w3.org/2000/svg","rect");a.setAttribute("rx",0),a.setAttribute("ry",0),a.setAttribute("width",t.width),a.setAttribute("height",t.height),l.insertBefore(a,l.firstChild)}}};function y(e){let t;switch(e){case 0:t="aggregation";break;case 1:t="extension";break;case 2:t="composition";break;case 3:t="dependency";break;case 4:t="lollipop";break;default:t="none"}return t}let g={parser:a.p,db:a.d,renderer:{setConf:function(e){r={...r,...e}},draw:u},styles:a.s,init:e=>{!e.class&&(e.class={}),e.class.arrowMarkerAbsolute=e.arrowMarkerAbsolute,a.d.clear()}}}}]);