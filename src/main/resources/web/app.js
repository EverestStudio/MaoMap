const viewport=document.getElementById('viewport'),map=document.getElementById('map'),worldSelect=document.getElementById('world-select'),playerSearch=document.getElementById('player-search');
let scale=1,offsetX=0,offsetZ=0,drag=null,activeWorld=null,tiles=new Set();
function paint(){map.style.transform=`translate(${offsetX}px,${offsetZ}px) scale(${scale})`;}
function addTile(x,z){const key=x+','+z;if(tiles.has(key))return;tiles.add(key);const img=document.createElement('img');img.src=`/tiles/${activeWorld}/tiles/${x}_${z}.png`;img.style.left=(x*256)+'px';img.style.top=(z*256)+'px';img.onerror=()=>{img.remove();tiles.delete(key)};map.append(img)}
function loadMarkers(){fetch('/api/markers?world='+activeWorld).then(r=>r.json()).then(items=>{map.querySelectorAll('.map-marker').forEach(el=>el.remove());items.forEach(m=>{const el=document.createElement('div');el.className='map-marker';const emoji=document.createElement('span');emoji.className='map-marker-emoji';emoji.textContent=m.emoji;const label=document.createElement('span');label.className='map-marker-label';label.textContent=m.name;el.append(emoji,label);el.title=m.name+' ('+m.x+', '+m.z+')';el.style.left=m.x+'px';el.style.top=m.z+'px';map.append(el)})}).catch(()=>{})}
setInterval(()=>{if(activeWorld)loadMarkers()},10000);
function loadWorld(world){activeWorld=world.id;tiles.clear();map.replaceChildren();scale=1;offsetX=-(world.spawnX||0)*scale;offsetZ=-(world.spawnZ||0)*scale;paint();if(zoomLevel)zoomLevel.textContent='100%';refreshPlayers();fetch('/tiles/'+activeWorld+'/index.json').then(r=>r.ok?r.json():[]).then(list=>{list.forEach(t=>addTile(t.x,t.z));document.getElementById('map-empty').hidden=list.length>0;loadMarkers()}).catch(()=>{document.getElementById('map-empty').hidden=false})}
fetch('/api/worlds').then(r=>r.json()).then(worlds=>{worldSelect.replaceChildren();worlds.forEach((w,i)=>{const o=document.createElement('option');o.value=w.id;o.textContent=w.name;worldSelect.append(o);if(i===0)activeWorld=w.id});if(activeWorld)loadWorld(worlds[0]);else document.getElementById('map-empty').hidden=false}).catch(()=>{worldSelect.innerHTML='<option>Нет доступных миров</option>'});
worldSelect.addEventListener('change',()=>{fetch('/api/worlds').then(r=>r.json()).then(worlds=>{const selected=worlds.find(w=>w.id===worldSelect.value);if(selected)loadWorld(selected)})});
function renderPlayerMarkers(players){map.querySelectorAll('.map-player').forEach(el=>el.remove());players.filter(p=>p.world===activeWorld).forEach(p=>{const el=document.createElement('div');el.className='map-player';const img=document.createElement('img');img.src='https://mc-heads.net/avatar/'+p.id+'/48';img.alt='';const direction=document.createElement('i');direction.className='player-direction';direction.style.transform='rotate('+(Number(p.yaw||0)+180)+'deg)';const name=document.createElement('span');name.textContent=p.name;el.append(direction,img,name);el.style.left=(p.x+0.5)+'px';el.style.top=(p.z+0.5)+'px';el.title=p.name+' ('+p.x+', '+p.z+')';map.append(el)})}\nlet latestPlayers=[];
function renderPlayerList(){
 const root=document.getElementById('players');root.replaceChildren();
 const query=(playerSearch.value||'').trim().toLocaleLowerCase();
 const visible=latestPlayers.filter(p=>p.name.toLocaleLowerCase().includes(query));
 if(!visible.length){root.innerHTML='<div class="empty">'+(latestPlayers.length?'Игрок не найден':'Сейчас никого нет онлайн')+'</div>';return}
 visible.forEach(p=>{
  const card=document.createElement('div');card.className='player';
  const img=document.createElement('img');img.className='avatar';img.src='https://mc-heads.net/avatar/'+p.id+'/68';img.alt='';
  const info=document.createElement('div');info.className='player-info';
  const name=document.createElement('div');name.className='player-name';name.textContent=p.name;
  const ping=document.createElement('div');ping.className='player-ping';ping.textContent=p.ping+' ms';info.append(name,ping);
  const dot=document.createElement('i');dot.className='ping-dot';card.append(img,info,dot);
  card.title='Показать '+p.name+' на карте';card.addEventListener('click',()=>{
   if(p.world!==activeWorld){const option=[...worldSelect.options].find(o=>o.value===p.world);if(option){worldSelect.value=p.world;worldSelect.dispatchEvent(new Event('change'))}}
   else{offsetX=-(p.x+0.5)*scale;offsetZ=-(p.z+0.5)*scale;paint();document.getElementById('coordinates').textContent='X '+p.x+' · Z '+p.z}
  });root.append(card);
 });
}
function refreshPlayers(){fetch('/api/players').then(r=>r.json()).then(players=>{latestPlayers=players;renderPlayerMarkers(players);document.getElementById('player-count').textContent=players.length;renderPlayerList()}).catch(()=>{})}
playerSearch.addEventListener('input',renderPlayerList);refreshPlayers();setInterval(refreshPlayers,5000);
const zoomLevel=document.getElementById('zoom-level');
function zoomAt(factor,anchorX=viewport.clientWidth/2,anchorY=viewport.clientHeight/2){
 const next=Math.max(.25,Math.min(8,scale*factor));
 if(next===scale)return;
 // Keep the world coordinate under the pointer stationary while scaling.
 offsetX=anchorX-viewport.clientWidth/2-(anchorX-viewport.clientWidth/2-offsetX)*(next/scale);
 offsetZ=anchorY-viewport.clientHeight/2-(anchorY-viewport.clientHeight/2-offsetZ)*(next/scale);
 scale=next;paint();
 if(zoomLevel)zoomLevel.textContent=Math.round(scale*100)+'%';
}
function zoom(factor){zoomAt(factor)}
document.getElementById('zoom-in').onclick=()=>zoom(1.25);
document.getElementById('zoom-out').onclick=()=>zoom(1/1.25);
if(zoomLevel){zoomLevel.textContent='100%';zoomLevel.title='Сбросить масштаб';zoomLevel.onclick=()=>{scale=1;paint();zoomLevel.textContent='100%'}}
viewport.addEventListener('wheel',e=>{
 e.preventDefault();
 const rect=viewport.getBoundingClientRect();
 zoomAt(e.deltaY<0?1.12:1/1.12,e.clientX-rect.left,e.clientY-rect.top);
},{passive:false});
viewport.addEventListener('dblclick',e=>{
 if(e.target.closest('button,input,select,.coords'))return;
 const rect=viewport.getBoundingClientRect();
 zoomAt(1.5,e.clientX-rect.left,e.clientY-rect.top);
});
viewport.addEventListener('pointerdown',e=>{if(e.target.closest('button,select,.coords'))return;drag={x:e.clientX,y:e.clientY,ox:offsetX,oz:offsetZ};viewport.classList.add('dragging');viewport.setPointerCapture(e.pointerId)});
viewport.addEventListener('pointermove',e=>{if(drag){offsetX=drag.ox+e.clientX-drag.x;offsetZ=drag.oz+e.clientY-drag.y;paint()}const rect=viewport.getBoundingClientRect();const x=Math.floor((e.clientX-rect.left-viewport.clientWidth/2-offsetX)/scale);const z=Math.floor((e.clientY-rect.top-viewport.clientHeight/2-offsetZ)/scale);document.getElementById('coordinates').textContent='X '+x+' · Z '+z});
viewport.addEventListener('pointerup',()=>{drag=null;viewport.classList.remove('dragging')});viewport.addEventListener('pointercancel',()=>{drag=null;viewport.classList.remove('dragging')});

document.getElementById('map-search').addEventListener('submit',e=>{e.preventDefault();const x=Number(document.getElementById('search-x').value),z=Number(document.getElementById('search-z').value);if(document.getElementById('search-x').value.trim()===''||document.getElementById('search-z').value.trim()===''||!Number.isFinite(x)||!Number.isFinite(z))return;offsetX=-(x+0.5)*scale;offsetZ=-(z+0.5)*scale;paint();document.getElementById('coordinates').textContent='X '+Math.floor(x)+' · Z '+Math.floor(z)});
