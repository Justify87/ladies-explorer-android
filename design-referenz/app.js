'use strict';
/* Historical offline design illustration; presentation and synthetic local interactions only. */
const PROFILES = window.DEMO_PROFILES;
const byId = id => PROFILES.find(p=>p.id===id);
const ROOTS = ['discover','search','saved','settings'];
const MODES = ['swipe','grid','map'];
const ASSETS = 'assets/profiles/';
let generation = 0; // Widerruft überholte asynchrone Demo-Aktionen.
const paths = {
 home:'<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
 search:'<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
 heart:'<path d="M20.8 4.6a5.6 5.6 0 0 0-7.9 0l-.9.9-.9-.9a5.6 5.6 0 0 0-7.9 7.9L12 21l8.8-8.5a5.6 5.6 0 0 0 0-7.9z"/>',
 star:'<path d="m12 2 3.2 6.8 7.5 1.1-5.4 5.3 1.3 7.5-6.6-3.5-6.6 3.5 1.3-7.5L1.3 9.9l7.5-1.1z"/>',
 settings:'<path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"/><path d="M19 13.7a7.5 7.5 0 0 0 0-3.4l2-1.5-2-3.5-2.4 1a7 7 0 0 0-2.9-1.6L13.3 2h-4l-.4 2.7A7 7 0 0 0 6 6.3l-2.4-1-2 3.5 2 1.5a7.5 7.5 0 0 0 0 3.4l-2 1.5 2 3.5 2.4-1a7 7 0 0 0 2.9 1.6l.4 2.7h4l.4-2.7a7 7 0 0 0 2.9-1.6l2.4 1 2-3.5z"/>',
 grid:'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
 cards:'<rect x="4" y="5" width="13" height="15" rx="1" transform="rotate(-15 4 5)"/><rect x="11" y="4" width="10" height="15" rx="1" transform="rotate(12 11 4)"/>',
 map:'<path d="m3 5 6-2 6 2 6-2v16l-6 2-6-2-6 2z"/><path d="M9 3v16M15 5v16"/><circle cx="12" cy="10" r="2"/>',
 pin:'<path d="M20 10c0 5-8 12-8 12S4 15 4 10a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/>',
 down:'<path d="m6 9 6 6 6-6"/>',right:'<path d="m9 5 7 7-7 7"/>',left:'<path d="m15 5-7 7 7 7"/>',back:'<path d="m18 12-12 0m6-6-6 6 6 6"/>',
 more:'<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
 close:'<path d="M5 5 19 19M19 5 5 19"/>',undo:'<path d="M9 10H3V4"/><path d="M3 10a9 9 0 1 1 1.5 6"/>',clock:'<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 3"/>',
 refresh:'<path d="M20 6v5h-5M4 18v-5h5"/><path d="M5.5 9a7 7 0 0 1 13-2L20 11M4 13l1.5 4a7 7 0 0 0 13-2"/>',
 bookmark:'<path d="M5 3h14v19l-7-5-7 5z"/>',check:'<path d="m4 12 5 5 11-11"/>',
 chevronup:'<path d="m6 15 6-6 6 6"/>',layers:'<path d="m12 2 9 5-9 5-9-5 9-5ZM3 12l9 5 9-5M3 17l9 5 9-5"/>',
 user:'<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',users:'<circle cx="9" cy="8" r="3"/><path d="M3 20a6 6 0 0 1 12 0M16 5a3 3 0 0 1 0 6m2 9a6 6 0 0 0-2-5"/>',
 file:'<path d="M6 2h9l5 5v15H6z"/><path d="M15 2v5h5M9 12h8M9 16h8"/>',
 chat:'<path d="M21 11a8 8 0 0 1-8 8H7l-4 3v-7a8 8 0 1 1 18-4Z"/>',phone:'<path d="M4 3h4l2 5-2 2a15 15 0 0 0 6 6l2-2 5 2v4a2 2 0 0 1-2 2C9 22 2 15 2 5a2 2 0 0 1 2-2z"/>',
 external:'<path d="M13 4h7v7M20 4l-9 9"/><path d="M20 14v6H4V4h6"/>',
 plus:'<path d="M12 5v14M5 12h14"/>',
 download:'<path d="M12 3v13m-5-5 5 5 5-5M4 18v3h16v-3"/>',share:'<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m9 11 6-5m-6 8 6 4"/>',
 code:'<path d="m8 7-5 5 5 5m8-10 5 5-5 5m-3-12-2 14"/>',database:'<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
 sliders:'<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2" fill="#343638"/><circle cx="16" cy="12" r="2" fill="#343638"/><circle cx="7" cy="18" r="2" fill="#343638"/>',
 tag:'<path d="M3 12 12 3h9v9l-9 9z"/><circle cx="17" cy="7" r="1"/>',
 info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v5m0-9v.5"/>',
 fingerprint:'<path d="M3 13V11a9 9 0 0 1 18 0v3M6 11a6 6 0 0 1 12 0v3M9 11v5m3-6v9m3-8v4M6 17v2"/>',
 trash:'<path d="M4 7h16M9 4h6M6 7l1 14h10l1-14M10 11v7m4-7v7"/>',
 pencil:'<path d="m4 16-.5 4.5L8 20 20 8l-4-4L4 16ZM14 6l4 4"/>',
 calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 10h18"/>',
 image:'<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8" cy="8" r="2"/><path d="m3 17 6-6 4 4 3-3 5 5"/>',
 alert:'<path d="M12 3 2 21h20L12 3z"/><path d="M12 10v5m0 3h.01"/>',
 filter:'<path d="M4 6h16l-6 7v5l-4 3v-8z"/>'
};
function icon(name,cls=''){return `<span class="icon ${cls}" aria-hidden="true"><svg viewBox="0 0 24 24">${paths[name]||paths.info}</svg></span>`;}
function esc(x){return String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function act(a,label,extra='',attrs=''){return `<button type="button" data-action="${a}" ${attrs} class="${extra}">${label}</button>`;}
const initFilters=()=>({text:'',place:'Leipzig',radius:20,availability:'Alle',ageMin:25,ageMax:35,category:'Alle',meeting:'Alle',onlyPhotos:false,hideLikes:false,feature:'',service:'',maxPages:5});
const initial=()=>({
 root:'discover',mode:'swipe',profileId:null,profileOrigin:null,deckIndex:0,
 deckOrder:PROFILES.map(x=>x.id),decisions:{},favorites:[],notes:{},noteDrafts:{},
 photoIndices:{},skipped:[],undoStack:[],gridHidden:[],
 filters:initFilters(),activeSearch:null,results:PROFILES.map(x=>x.id),
 selectedMap:'v',mapFavorites:false,mapResolve:{running:false,completed:0,generation:0},
 sectionOpen:[],searchExpanded:[],savedTab:'favorites',savedSort:'source',
 savedSearchOpen:false,savedSearches:[{id:'sample',name:'Leipzig · 20 km',filters:initFilters(),unread:0}],
 dialog:null,toast:'',viewState:'normal',settings:{debug:false,capture:'Aus',captureEntries:0,showLikes:false,dbStatus:'nicht verbunden'},
 loading:false,previewCount:'unbekannt',lastAction:null,encounters:{},priceLists:{},nextRecordId:1,
});
let S=initial(); let toastHandle=null; let lastScroll=0; let dialogReturnFocus=null;let pendingEditor=null;
const app=document.getElementById('app');
const img=p=>`${ASSETS}${p.photos[S.photoIndices[p.id]||0]||p.photos[0]}`;
const isLiked=id=>S.decisions[id]==='like';const isFav=id=>S.favorites.includes(id);
function buttonStar(id,css='hero-favorite'){return act('favorite',icon('star',isFav(id)?'fill lg':'lg'),`${css} icon-button ${isFav(id)?'is-favorite':''}`,`data-id="${id}" aria-label="${isFav(id)?'Favorit entfernen':'Als Favorit markieren'}" aria-pressed="${isFav(id)}"`);}
function header(){
 if(S.profileId){let p=byId(S.profileId);return `<header class="app-header">${act('back',icon('back'),'icon-button header-back','aria-label="Zurück"')}<h1 class="header-name">${esc(p.name)}</h1>${act('favorite',icon('star',isFav(p.id)?'fill lg':'lg'),'icon-button header-star',`data-id="${p.id}" aria-label="Favorit umschalten" aria-pressed="${isFav(p.id)}"`)}${act('menu',icon('more'),'icon-button','aria-label="Profilmenü"')}</header>`;}
 return `<header class="app-header"><h1 class="brand">Ladies</h1><div class="header-actions">${act('header-search',icon('search'),'circle-button','aria-label="Zur Suche"')}${act('menu',icon('more'),'icon-button','aria-label="Weitere Optionen"')}</div></header>`;
}
function rootNav(){let tabs=[['discover','Entdecken','home'],['search','Suche','search'],['saved','Gespeichert','heart'],['settings','Einstellungen','settings']];return `<nav class="root-nav" aria-label="Hauptnavigation">${tabs.map(([key,label,ico])=>act('root',`<span class="nav-icon">${icon(ico)}</span><span>${label}</span>`,S.root===key?'active':'',`data-value="${key}" ${S.root===key?'aria-current="page"':''}`)).join('')}</nav>`;}
function modeSwitch(){return `<div class="mode-switch" role="group" aria-label="Ansicht wählen">${[['swipe','Swipe','cards'],['grid','Grid','grid'],['map','Karte','map']].map(([m,label,i])=>act('mode',icon(i)+label,`mode-button ${S.mode===m?'active':''}`,`data-value="${m}" aria-pressed="${S.mode===m}"`)).join('')}</div>`;}
function contextStrip(){
 if(S.mode==='map')return `<div class="context-strip"><span class="context-piece primary">Lokaler Bestand</span><button type="button" class="context-piece context-toggle" data-action="map-favs" aria-label="Nur Favoriten auf Karte anzeigen" aria-pressed="${S.mapFavorites}">Favoriten <span class="switch ${S.mapFavorites?'on':''}"></span></button></div>`;
 return `<div class="context-strip">${act('quick-place',icon('pin')+`${S.activeSearch?`${esc(S.activeSearch.place)} · ${S.activeSearch.radius} km`:'Lokaler Bestand · Leipzig'}`,'context-piece primary','aria-label="Suchkontext öffnen"')}${act('quick-availability',esc(S.activeSearch?.availability||'Alle')+icon('down','sm'),'context-piece secondary','aria-label="Schnellfilter Verfügbarkeit wählen"')}</div>`;
}
function hero(p,type='swipe'){
 return `<article class="profile-hero ${type==='swipe'?'swipe':'detail'}" data-hero="${p.id}" aria-label="Galerie von ${esc(p.name)}"><img alt="Porträt von ${esc(p.name)}" src="${img(p)}" draggable="false"><div class="hero-shade"></div>
 ${act('photo-prev','', 'hero-gallery-tap left',`data-id="${p.id}" aria-label="Vorheriges Foto"`)}${act('photo-next','','hero-gallery-tap right',`data-id="${p.id}" aria-label="Nächstes Foto"`)}
 <div class="hero-info"><h2>${esc(p.name)}, ${p.age}</h2><p>${esc(p.place)}</p></div>${type==='swipe'?buttonStar(p.id):''}
 </article>`;
}
function profileDetails(p){
 const sections=[
  ['services','Dienste & Merkmale','layers'],
  ['analysis','Analyse & Identität','fingerprint'],
  ['notes','Meine Notiz','pencil'],
  ['encounters','Meine Begegnungen','clock'],
  ['prices','Manuelle Angebotspreise','file'],
  ['history','Verlauf & Anzeigen','clock'],
 ];
 const open=k=>S.sectionOpen.includes(k);
 const aboutExpanded=open('about');
 const contact=p.contact?`<section class="labelled-group"><h3>Kontakt</h3><div class="contact-row">
   ${act('contact',icon('phone')+'Telefon','','data-value="Telefon"')}
   ${act('contact',icon('chat')+'WhatsApp','','data-value="WhatsApp"')}
   ${act('contact',icon('external')+'Original','','data-value="Originalprofil"')}
  </div></section>`:'';
 return `<div class="profile-facts"><div>${icon('user','sm')} ${p.height} cm</div><div>${icon('chat','sm')} ${esc(p.lang)}</div></div>
 <section class="about labelled-group"><h3>Über mich</h3><p>${esc(aboutExpanded?p.about:p.about.slice(0,105))}${!aboutExpanded&&p.about.length>105?'…':''}</p>
 ${p.about.length>105?act('toggle-section',aboutExpanded?'Weniger anzeigen':'Mehr anzeigen','link-action','data-value="about"'):''}</section>
 ${contact}
 <div class="panel-group labelled-group">${sections.map(([key,label,ico])=>`<section>
  <button type="button" class="section-row" data-action="toggle-section" data-value="${key}" aria-expanded="${open(key)}">
  ${icon(ico)}<span>${label}${key==='notes'&&S.notes[p.id]?' · gespeichert':''}${key==='notes'&&noteDirty(p.id)?' · nicht gespeichert':''}</span>${icon(open(key)?'chevronup':'right','sm')}
  </button>${open(key)?`<div class="section-body section-open">${detailBody(key,p)}</div>`:''}</section>`).join('')}</div>`;
}
function noteValue(id){return Object.hasOwn(S.noteDrafts,id)?S.noteDrafts[id]:(S.notes[id]||'');}
function noteDirty(id){return Object.hasOwn(S.noteDrafts,id)&&S.noteDrafts[id]!== (S.notes[id]||'');}
function euro(value){return Number(value).toLocaleString('de-DE',{style:'currency',currency:'EUR'});}
function detailBody(key,p){switch(key){
 case 'services':return `<strong>Dienste</strong><p>${p.services.map(esc).join(' · ')}</p><p class="minor">Merkmale: ${p.features.map(esc).join(' · ')}</p><p class="minor">Weitere Filterbelege: unbekannt</p>`;
 case 'analysis':return `<p>Keine bestätigte Identitätszusammenführung.</p><p class="minor">Keine belegten Identitätskandidaten vorhanden.</p>`;
 case 'notes':return `<label for="profileNote">Private Personennotiz</label><textarea id="profileNote" name="profileNote" placeholder="Deine Notiz …">${esc(noteValue(p.id))}</textarea>
  ${noteDirty(p.id)?'<p class="editor-warning" role="status">Ungespeicherter Entwurf</p>':''}
  <div class="inline-actions">${act('save-note',icon('check','sm')+' Speichern','',`data-id="${p.id}"`)}${act('clear-note','Löschen','',`data-id="${p.id}"`)}</div>
  <p class="minor">Personennotiz und Begegnungsnotiz bleiben getrennt.</p>`;
 case 'encounters':{
  const items=S.encounters[p.id]||[];
  return `<p class="minor">Persönliche Begegnungen, ausschließlich lokal in dieser Vorschau.</p>
   ${items.length?items.map(r=>`<div class="record-row"><div><strong>${esc(r.date)}</strong><p>${r.total!==''?euro(r.total):'Preis unbekannt'}${r.rating?' · Gesamtbewertung '+r.rating+'/5':''}</p></div>
   ${act('edit-encounter','Bearbeiten','action-outline',`data-id="${p.id}" data-record="${r.id}"`)}</div>`).join(''):'<p class="record-empty">Noch keine Begegnung erfasst.</p>'}
   ${act('new-encounter',icon('plus','sm')+' Begegnung erfassen','action-outline full-width',`data-id="${p.id}"`)}`;
 }
 case 'prices':{
  const items=S.priceLists[p.id]||[];
  return `<p class="minor">Manuell erfasste Angebotspreise; kein Nachweis aktueller Gültigkeit.</p>
   ${items.length?items.map(r=>`<div class="record-row"><div><strong>${esc(r.date)}</strong><p>${r.items.split('\n').filter(Boolean).length} Position(en) · manuell erfasst</p></div>
   ${act('edit-price','Bearbeiten','action-outline',`data-id="${p.id}" data-record="${r.id}"`)}</div>`).join(''):'<p class="record-empty">Noch keine Preisliste erfasst.</p>'}
   ${act('new-price',icon('plus','sm')+' Preisliste erfassen','action-outline full-width',`data-id="${p.id}"`)}`;
 }
 case 'history':return `<p class="minor">Noch keine bestätigten Quellenänderungen oder verknüpften Anzeigen in den Beispieldaten.</p>`;
 default:return '';
}}
function decisionRail(){return `<div class="decision-rail" aria-label="Profilentscheidung">${[['undo','Undo','undo',''],['dislike','Nein','close','reject'],['skip','Später','clock',''],['like','Gefällt','heart','like']].map(([a,label,ico,c])=>`<button type="button" class="decision ${c}" data-action="${a}" ${a==='undo'&&!S.undoStack.length?'disabled':''}><span class="decision-icon">${icon(ico)}</span><span>${label}</span></button>`).join('')}</div>`;}
function results(){return S.results.filter(id=>!!byId(id));}
function currentDeck(){return byId(S.deckOrder[S.deckIndex]);}
function resultState(which){
 if(S.viewState==='normal')return '';
 if(S.viewState==='partial')return `<div class="status-banner">Teilresultat · Einige Einträge sind noch nicht bestätigt. Keine Entfernung wird daraus abgeleitet.</div>`;
 if(S.viewState==='loading')return `<div class="load-state"><span class="loading-spinner"></span><h3>${which} wird geladen</h3><p>Bitte einen Moment warten.</p>${act('retry','Ansicht laden','action-outline')}</div>`;
 if(S.viewState==='empty')return `<div class="empty-state">${icon('search')}<h3>Keine Treffer</h3><p>Für diese Ansicht sind gerade keine passenden Profile vorhanden.</p>${act('retry','Ergebnisse anzeigen','action-outline')}</div>`;
 if(S.viewState==='error')return `<div class="empty-state">${icon('alert')}<h3>Vorgang fehlgeschlagen</h3><p>Die bisherigen Daten bleiben erhalten.</p>${act('retry',icon('refresh')+' Erneut versuchen','action-primary')}</div>`;
 return '';
}
function discoverScreen(){
 let stateSpecial=resultState('Entdecken');
 let content='';
 if(S.viewState==='loading'||S.viewState==='error'||S.viewState==='empty')content=stateSpecial;
 else if(S.mode==='swipe'){
  const p=currentDeck();
  content= (S.viewState==='partial'?stateSpecial:'')+(p?`<div class="screen-pane full-bleed">${hero(p)}<p class="hero-hint">${icon('down','sm')} Zum Profil scrollen</p>${profileDetails(p)}</div>`:`<div class="empty-state">${icon('check')}<h3>Alle Profile angesehen</h3><p>Du hast das Ende des aktuellen Decks erreicht.</p>${act('reset-deck',icon('refresh')+' Deck neu starten','action-primary')}</div>`);
 }else if(S.mode==='grid'){
  const ids=results().filter(id=>!S.gridHidden.includes(id));
  content=`<div class="screen-pane">${S.viewState==='partial'?stateSpecial:''}<div class="grid-count">${ids.length} sichtbare Profile</div>${ids.length?`<div class="profiles-grid">${ids.map(id=>profileCard(byId(id))).join('')}</div>`:`<div class="empty-state"><h3>Keine sichtbaren Profile</h3>${act('reset-deck','Deck erneut zeigen','action-outline')}</div>`}</div>`;
 }else content=mapScreen();
 return `<div class="content-scroll" data-scroll="discover">${contextStrip()}${modeSwitch()}${content}</div>${S.mode==='swipe'&&currentDeck()&&S.viewState!=='loading'&&S.viewState!=='error'&&S.viewState!=='empty'?decisionRail():''}${rootNav()}`;
}
function profileCard(p){return `<article class="profile-card" data-profile="${p.id}"><img src="${ASSETS+p.photos[0]}" alt="Porträt von ${esc(p.name)}" loading="lazy"><div class="hero-shade"></div>${act('open-profile','','card-open',`data-id="${p.id}" aria-label="Profil ${esc(p.name)} öffnen"`)}<div class="card-label"><strong>${esc(p.name)}, ${p.age}</strong><small>${esc(p.place)}</small></div>${act('grid-like',icon('heart',isLiked(p.id)?'fill':'') ,`icon-button card-like ${isLiked(p.id)?'is-liked':''}`,`data-id="${p.id}" aria-label="${esc(p.name)} gefällt mir"`)}${isFav(p.id)?act('favorite',icon('star','fill sm'),'icon-button card-fav',`data-id="${p.id}" aria-label="Favorit entfernen"`):''}</article>`;}
function mapScreen(){
 const subset=S.mapFavorites?PROFILES.filter(p=>isFav(p.id)):PROFILES;
 const p=subset.find(p=>p.id===S.selectedMap)||subset[0];
 // Marker/Cluster sind reine Illustrationen ungefähren Ortsbezugs (keine Geo-Koordinaten).
 const clusters=[{pos:[29,43],ids:['e','a','v']},{pos:[75,48],ids:['s','j']}];
 let markers='';
 if(S.mapFavorites){markers=subset.map(z=>mapMarker(z)).join('');}
 else {markers=['n','m','l'].map(id=>mapMarker(byId(id))).join('')+clusters.map((c,i)=>`<button type="button" class="map-marker cluster" data-action="map-cluster" data-value="${i}" style="left:${c.pos[0]}%;top:${c.pos[1]}%" aria-label="${c.ids.length} Profile an diesem Ort auswählen">${c.ids.length}</button>`).join('');}
 return `<div class="screen-pane full-bleed">${S.viewState==='partial'?resultState('Karte'):''}<div class="map-surface" role="group" aria-label="Illustrative Karte um Leipzig, ungefähre Ortslagen">${subset.length?markers:''}<span class="map-label city">Leipzig</span><span class="map-label suburb">Markkleeberg</span><div class="map-footer"><span>${icon('info','sm')} Ungefähre Ortslagen</span>${act(S.mapResolve.running?'cancel-resolve':'resolve-locations',icon(S.mapResolve.running?'close':'layers','sm')+(S.mapResolve.running?' Abbrechen':' Standorte auflösen'),'','') }</div></div>${S.mapResolve.running?`<div class="status-banner" role="status">Standorte werden aufgelöst: ${S.mapResolve.completed} von 8 · Abbruch möglich</div>`:''}${!subset.length?`<div class="empty-state"><h3>Noch keine Favoriten</h3><p>Markiere ein Profil mit dem Stern, um es auf dieser Karte zu sehen.</p>${act('map-favs','Alle anzeigen','action-outline')}</div>`:p?`<div class="selected-card"><div class="selected-thumb"><img src="${ASSETS+p.photos[0]}" alt=""></div><div class="selected-info"><b>${esc(p.name)}, ${p.age}</b><small>${esc(p.district)}</small></div>${act('open-profile','Profil öffnen','action-primary',`data-id="${p.id}"`)}</div>`:''}</div>`;
}
function mapMarker(p){return `<button type="button" class="map-marker person ${S.selectedMap===p.id?'selected':''}" data-action="select-marker" data-id="${p.id}" style="left:${p.position[0]}%;top:${p.position[1]}%" aria-label="${esc(p.name)} auf Karte auswählen"><img src="${ASSETS+p.photos[0]}" alt=""></button>`;}
function profileScreen(){const p=byId(S.profileId);if(!p)return '';
 return `<div class="content-scroll" data-scroll="profile"><div class="screen-pane full-bleed">${hero(p,'detail')}${profileDetails(p,true)}</div></div>`;
}
function searchScreen(){let f=S.filters;let expanded=k=>S.searchExpanded.includes(k);const sections=[['category','Rubrik','grid'],['meeting','Treffpunkt','pin'],['features','Kategorien & Merkmale','tag'],['services','Services','layers'],['more','Weitere Optionen','sliders']];return `<div class="content-scroll" data-scroll="search"><div class="screen-pane"><div class="screen-heading search-title"><h2>Suche</h2>${act('saved-searches',icon('bookmark'),'circle-button','aria-label="Gespeicherte Suchen"')}</div>${S.activeSearch?`<div class="last-run">${icon('check','sm')} Aktiver Suchlauf · ${results().length} lokale Treffer ${act('last-results','Ergebnisse ansehen','link-action')}</div>`:''}<div class="search-top">${icon('search')}<input class="text-input" name="text" value="${esc(f.text)}" placeholder="Filter suchen" aria-label="Filtertext"></div>
 <button type="button" class="filter-line" data-action="change-place">${icon('pin')}<span style="flex:1">Ort <span class="small-detail" style="display:block">${esc(f.place)}</span></span>${icon('right')}</button>
 <label class="form-section-label">Umkreis</label><div class="radius-row">${[5,10,20,30,50].map(r=>act('radius',r+' km',r===f.radius?'selected':'',`data-value="${r}" aria-pressed="${r===f.radius}"`)).join('')}${act('radius-more',icon('right','sm'),'arrow-radius','aria-label="Weitere Radien"')}</div>
 <label class="form-section-label">Verfügbarkeit</label><div class="segment-control" role="group" aria-label="Verfügbarkeit">${['Alle','Jetzt','Heute'].map(a=>act('filter-avail',a,a===f.availability?'selected':'',`data-value="${a}" aria-pressed="${a===f.availability}"`)).join('')}</div>
 <div class="space-between" style="margin:10px 2px 0"><span class="form-section-label" style="margin:0">Alter</span><span style="font-size:12px">${f.ageMin}–${f.ageMax} Jahre</span></div><div class="filter-age"><div class="range-visual"><input type="range" name="ageMin" min="18" max="55" value="${f.ageMin}" aria-label="Mindestalter"><input type="range" name="ageMax" min="18" max="55" value="${f.ageMax}" aria-label="Höchstalter"></div></div>
 <div class="filter-groups">${sections.map(([key,label,ic])=>`<div><button class="filter-line" type="button" data-action="expand-filter" data-value="${key}" aria-expanded="${expanded(key)}">${icon(ic)}<span style="flex:1">${label}</span>${icon(expanded(key)?'chevronup':'right','sm')}</button>${expanded(key)?`<div class="filter-editor">${filterFields(key)}</div>`:''}</div>`).join('')}</div>
 <div class="search-footer"><div class="split-actions">${act('reset-filters',icon('refresh')+' Reset','action-outline')}${S.loading?act('cancel-search',icon('close')+' Abbrechen','action-outline'):act('search',icon('search')+' Suchen','action-primary')}</div><div class="preview-count">${S.loading?'Suche läuft · zuletzt bestätigter Bestand bleibt erhalten':'Trefferzahl nach Ausführung verfügbar'}</div></div></div></div>${rootNav()}`;}
function filterFields(key){const f=S.filters;
 const option=(k,arr)=>`<select name="${k}" aria-label="${k}">${arr.map(x=>`<option ${f[k]===x?'selected':''}>${esc(x)}</option>`).join('')}</select>`;
 if(key==='category')return `<label>Rubrik</label>${option('category',['Alle','Begleitung','Kultur','Café'])}`;
 if(key==='meeting')return `<label>Treffpunkt</label>${option('meeting',['Alle','Leipzig','Markkleeberg','Unbekannt'])}`;
 if(key==='features')return `<label>Eigenschaften</label><label><input name="onlyPhotos" type="checkbox" ${f.onlyPhotos?'checked':''}> Nur mit Foto</label><label>Größe / Merkmal</label>${option('feature',['','165–170 cm','170 cm und größer'])}`;
 if(key==='services')return `<label>Dienste</label>${option('service',['','Begleitung','Gespräche','Kultur','Café'])}`;
 if(key==='more')return `<label><input name="hideLikes" type="checkbox" ${f.hideLikes?'checked':''}> Likes/Dislikes ausblenden</label><label>Seitenlimit</label><input type="number" name="maxPages" min="1" max="50" value="${f.maxPages}"><div class="muted" style="font-size:11px">Weitere Filter werden hier nicht dargestellt.</div>`;
 return '';
}
function savedScreen(){const tab=S.savedTab;const opts=[['favorites','Favoriten'],['likes','Likes'],['dislikes','Dislikes']];const ids=PROFILES.filter(p=>tab==='favorites'?isFav(p.id):S.decisions[p.id]===(tab==='likes'?'like':'dislike'));
 return `<div class="content-scroll" data-scroll="saved"><div class="screen-pane"><div class="screen-heading"><h2>Gespeichert</h2></div><div class="saved-tabs">${opts.map(([key,label])=>act('saved-tab',label+' ('+(key==='favorites'?S.favorites.length:Object.values(S.decisions).filter(x=>x===(key==='likes'?'like':'dislike')).length)+')',tab===key?'selected':'',`data-value="${key}"`)).join('')}</div><div class="space-between" style="margin-bottom:10px"><h3>${ids.length} gespeicherte Profile</h3>${act('saved-options',icon('more'),'icon-button','aria-label="Sortierung und Prüfung"')}</div>
 <div class="stack">${ids.length?(S.savedSort==='name'?[...ids].sort((a,b)=>a.name.localeCompare(b.name,'de')):S.savedSort==='reverse'?[...ids].reverse():ids).map(p=>`<div class="saved-row"><div class="saved-thumb"><img src="${ASSETS+p.photos[0]}" alt=""></div><div class="text"><b>${esc(p.name)}, ${p.age}</b><small>${esc(p.place)} · Status unbekannt</small></div>${act('open-profile',icon('right'),'icon-button',`data-id="${p.id}" aria-label="${esc(p.name)} öffnen"`)}</div>`).join(''):`<div class="empty-state" style="min-height:180px">${icon('heart')}<h3>Hier ist noch nichts gespeichert</h3><p>Like oder Favorit lassen die Profile in dieser Liste erscheinen.</p></div>`}</div>
 ${act('toggle-saved-search',icon('bookmark')+' Gespeicherte Suchen '+icon(S.savedSearchOpen?'chevronup':'down','sm'),'saved-section-tab',`aria-expanded="${S.savedSearchOpen}"`)}${S.savedSearchOpen?savedSearchCards():''}
 </div></div>${rootNav()}`;}
function savedSearchCards(){return `${S.savedSearches.map(s=>`<div class="saved-search"><strong>${esc(s.name)}</strong><p>${esc(s.filters.place)} · ${s.filters.radius} km · ${esc(s.filters.availability)} ${s.unread?'· '+s.unread+' Neu':''}</p><div class="saved-search-actions">${act('run-saved','Ausführen','',`data-id="${s.id}"`)}${act('edit-saved','Bearbeiten','',`data-id="${s.id}"`)}${act('rename-saved','Umbenennen','',`data-id="${s.id}"`)}${act('ack-saved',s.unread?'Neu quittieren':'Keine Neuigkeiten','',`data-id="${s.id}" ${s.unread?'':'disabled'}`)}${act('delete-saved','Löschen','',`data-id="${s.id}"`)}</div></div>`).join('')}${act('create-saved','Aktuelle Filter als Suche speichern','action-outline full-width top-gap')}`;}
function settingsScreen(){let x=S.settings;return `<div class="content-scroll" data-scroll="settings"><div class="screen-pane"><div class="screen-heading"><h2>Einstellungen</h2></div>
 <section class="settings-group"><div class="setting-top"><h3>Lokale Datenbasis</h3><div class="ready">${icon('info','sm')} Datenbank ${esc(x.dbStatus)}</div></div>${[['database','Schema','—'],['users','Personen','—'],['file','Inserate','—']].map(([i,k,v])=>`<div class="stat-line">${icon(i)}<strong>${k}</strong><span>${v}</span></div>`).join('')}${act('refresh-status',icon('refresh','sm')+' Status aktualisieren','action-outline')}</section>
 <section class="settings-group"><h3>Entdecken</h3>${settingToggle('showLikes','Likes erneut zeigen','Bereits gelikte Profile im Deck', 'heart')}</section>
 <section class="settings-group"><h3>Diagnose</h3>${settingToggle('debug','Debug-Details','Protokolle nur bewusst teilen','code')}<div class="setting-buttons">${act('save-log',icon('download','sm')+' Log speichern','action-outline')}${act('share-log',icon('share','sm')+' Teilen','action-outline')}</div></section>
 <section class="settings-group"><h3>HTTP-Aufzeichnung</h3><div class="segment-control" role="group" aria-label="HTTP-Aufzeichnung">${['Aus','Metadaten','Textantworten'].map(m=>act('capture-mode',m,x.capture===m?'selected':'',`data-value="${m}" aria-pressed="${x.capture===m}"`)).join('')}</div><p class="setting-description">${x.capture==='Aus'?'Nach jedem Neustart ausgeschaltet':`Ausgewählter Modus: ${x.capture}. Ohne Backend werden keine HTTP-Ereignisse aufgezeichnet.`}</p><div class="rule"></div><div class="setting-buttons">${act('clear-capture',icon('trash','sm')+' Sitzung löschen','action-outline')}</div></section>

 </div></div>${rootNav()}`;}
function settingToggle(k,label,sub,ic){return `<button type="button" class="setting-toggle" data-action="setting-toggle" data-value="${k}" aria-pressed="${S.settings[k]}">${icon(ic)}<span class="setting-copy"><strong>${label}</strong><small>${sub}</small></span><span class="switch ${S.settings[k]?'on':''}"></span></button>`;}
function dialog(){if(!S.dialog)return '';
 let d=S.dialog; let body='';
 if(d.type==='menu')body=`<h2>Weitere Optionen</h2>${[['saved-searches','Gespeicherte Suchen','bookmark'],['reset-deck','Deck neu starten','refresh'],['root-settings','Einstellungen','settings']].map(([a,label,ic])=>act(a,icon(ic)+label+icon('right','sm'),'menu-row')).join('')}`;
 else if(d.type==='availability')body=`<h2>Verfügbarkeit</h2>${['Alle','Jetzt','Heute'].map(t=>act('quick-avail-value',icon('clock')+t+icon('right','sm'),'menu-row',`data-value="${t}"`)).join('')}`;
 else if(d.type==='place')body=`<h2>Suchort</h2><p>Dieser Suchort wird als Filter übernommen.</p><label>Ort<input class="field" name="modalPlace" id="modalPlace" value="${esc(S.filters.place)}"></label><div class="modal-actions">${act('close-dialog','Abbrechen','action-outline')}${act('save-place','Übernehmen','action-primary')}</div>`;
 else if(d.type==='contact')body=`<h2>${esc(d.label)}</h2><p>Für dieses Beispielprofil ist keine Kontaktadresse hinterlegt. Es wird nichts geöffnet oder versendet.</p>${act('close-dialog','Schließen','action-primary full-width')}`;
 else if(d.type==='cluster')body=`<h2>Profile am ungefähren Ort</h2>${d.ids.map(id=>{let p=byId(id);return act('cluster-select',icon('user')+esc(p.name)+', '+p.age+icon('right','sm'),'menu-row',`data-id="${id}"`)}).join('')}`;
 else if(d.type==='last-results')body=`<h2>Letzte Suchergebnisse</h2><p>${results().length} lokale Treffer des zuletzt ausgeführten Suchlaufs.</p>${results().map(id=>{const p=byId(id);return act('open-result',icon('user')+esc(p.name)+', '+p.age+icon('right','sm'),'menu-row',`data-id="${id}"`)}).join('')}`;
  else if(d.type==='discard-note')body=`<h2>Ungespeicherte Notiz</h2><p>Dein Entwurf wurde noch nicht gespeichert.</p><div class="modal-actions">${act('close-dialog','Weiter bearbeiten','action-outline')}${act('discard-note','Entwurf verwerfen','action-primary')}</div>`;
  else if(d.type==='delete-note')body=`<h2>Notiz löschen?</h2><p>Die gespeicherte Personennotiz wird lokal entfernt.</p><div class="modal-actions">${act('close-dialog','Abbrechen','action-outline')}${act('confirm-delete-note','Löschen','action-primary')}</div>`;
  else if(d.type==='discard-record')body=`<h2>Ungespeicherte Änderungen</h2><p>Die Angaben wurden noch nicht gespeichert.</p><div class="modal-actions">${act('resume-record','Weiter bearbeiten','action-outline')}${act('discard-record','Änderungen verwerfen','action-primary')}</div>`;
  else if(d.type==='encounter-editor')body=recordEditor('encounter',d);
  else if(d.type==='price-editor')body=recordEditor('price',d);
  else if(d.type==='saved-searches')body=`<h2>Gespeicherte Suchen</h2>${savedSearchCards()}`;
 else if(d.type==='name-saved')body=`<h2>${d.mode==='new'?'Suche speichern':'Suche umbenennen'}</h2><label>Name<input class="field" name="savedName" id="savedName" maxlength="60" value="${esc(d.value||'')}"></label><div class="modal-actions">${act('close-dialog','Abbrechen','action-outline')}${act('save-saved-name','Speichern','action-primary')}</div>`;
 else if(d.type==='delete-saved')body=`<h2>Gespeicherte Suche löschen?</h2><p>Die gespeicherte Suche und ihre Neu-Kennzeichnung werden entfernt.</p><div class="modal-actions">${act('close-dialog','Abbrechen','action-outline')}${act('confirm-delete-saved','Löschen','action-primary')}</div>`;

 else if(d.type==='saved-options')body=`<h2>Gespeicherte Profile</h2>${act('check-status',icon('refresh')+' Status prüfen','menu-row')}${act('sort-saved',icon('sliders')+' Sortierung umkehren','menu-row')}${act('saved-searches',icon('bookmark')+' Gespeicherte Suchen','menu-row')}`;
 else if(d.type==='radius')body=`<h2>Umkreis</h2>${[5,10,20,30,50,70,100].map(x=>act('radius',`${x} km`,'menu-row',`data-value="${x}"`)).join('')}`;
 else body=`<h2>Hinweis</h2><p>${esc(d.message||'Vorgang nicht verfügbar.')}</p>${act('close-dialog','Schließen','action-primary')}`;
 body=body.replace('<h2>', '<h2 id="dialog-title">');
 return `<div class="modal-backdrop" role="presentation"><section role="dialog" aria-modal="true" aria-labelledby="dialog-title" class="modal-panel">${act('close-dialog',icon('close'),'icon-button close-modal','aria-label="Dialog schließen"')}${body}</section></div>`;
}
function recordEditor(type,d){
 const isEncounter=type==='encounter';
 const entries=(isEncounter?S.encounters:S.priceLists)[d.id]||[];
 const r=entries.find(x=>x.id===d.record)||{};
 const title=isEncounter?'Begegnung':'Preisliste';
 const rating=(field,label)=>`<label>${label} (optional)<select name="${field}"><option value="">Unbekannt</option>${[1,2,3,4,5].map(v=>`<option value="${v}" ${r[field]==v?'selected':''}>${v} / 5</option>`).join('')}</select></label>`;
 return `<h2>${r.id?title+' bearbeiten':title+' erfassen'}</h2>
  <p class="minor">Lokaler Entwurf. Fachliche Validierung und Persistenz übernimmt später die native App.</p>
  <form id="recordEditor" class="record-form" autocomplete="off">
   <label>${isEncounter?'Datum und Uhrzeit':'Datum'}
    <input name="date" type="${isEncounter?'datetime-local':'date'}" required value="${esc(r.date||'')}"></label>
   ${isEncounter?`
    <label>Dauer (Minuten; optional) <input name="duration" type="number" min="0" step="1" value="${esc(r.duration??'')}"></label>
    <label>Grundpreis (€; optional) <input name="base" type="number" min="0" step="0.01" inputmode="decimal" value="${esc(r.base??'')}"></label>
    <label>Bezahlter Gesamtbetrag (€; optional) <input name="total" type="number" min="0" step="0.01" inputmode="decimal" value="${esc(r.total??'')}"></label>
    ${rating('rating','Gesamtbewertung')}${rating('sympathy','Sympathie')}
    <label>Persönliche Leistungszeilen (optional)<textarea name="services" placeholder="Eine Leistung pro Zeile; Beträge nicht automatisch berechnet">${esc(r.services||'')}</textarea></label>
    <label>Begegnungsnotiz (getrennt von Personennotiz) <textarea name="encounterNote">${esc(r.note||'')}</textarea></label>`:
    `<label>Preisliste – Positionszeilen <textarea name="items" required placeholder="Eine Position pro Zeile: Leistung | EUR-Betrag oder unbekannt">${esc(r.items||'')}</textarea></label>`}
  </form><div class="modal-actions">${r.id?act('delete-record','Löschen','action-outline'):act('close-dialog','Abbrechen','action-outline')}
  ${act('save-record','Speichern','action-primary')}</div>`;
}
function render(opts={}){
 const focusEl=document.activeElement;
 const wasInApp=app.contains(focusEl);
 const identity=wasInApp?{
  action:focusEl.closest('[data-action]')?.dataset.action,
  value:focusEl.closest('[data-action]')?.dataset.value,
  id:focusEl.closest('[data-action]')?.dataset.id,
  name:focusEl.name,
  selStart:typeof focusEl.selectionStart==='number'?focusEl.selectionStart:null,
 }:null;
 if(!opts.resetScroll){const old=app.querySelector('.content-scroll');if(old)lastScroll=old.scrollTop;}
 const oldScroll=opts.scrollTo??(!opts.resetScroll?lastScroll:0);
 const content=S.profileId?profileScreen():S.root==='discover'?discoverScreen():S.root==='search'?searchScreen():S.root==='saved'?savedScreen():settingsScreen();
 app.innerHTML=header()+content+(S.toast?`<div role="status" class="toast"><span>${esc(S.toast)}</span>${act('dismiss-toast','OK','')}</div>`:'')+dialog();
 const sc=app.querySelector('.content-scroll');
 if(sc)sc.scrollTop=opts.resetScroll?0:oldScroll;
 const dlg=app.querySelector('[role="dialog"]');
 if(dlg && opts.focusDialog){(dlg.querySelector('input,textarea')||dlg.querySelector('button'))?.focus();return;}
 if(opts.resetScroll||opts.focusDialog||!identity)return;
 let candidates=identity.name?[...app.querySelectorAll(`[name="${identity.name}"]`)]:[...app.querySelectorAll('[data-action]')].filter(e=>e.dataset.action===identity.action&&e.dataset.value===identity.value&&e.dataset.id===identity.id);
 const target=candidates[0];
 if(target){target.focus({preventScroll:true});if(identity.selStart!==null&&typeof target.setSelectionRange==='function')try{target.setSelectionRange(identity.selStart,identity.selStart);}catch{}}
}
function notify(s){S.toast=s;render();if(toastHandle)clearTimeout(toastHandle);toastHandle=setTimeout(()=>{if(S.toast===s){S.toast='';render();}},2700);}
function saveScroll(){let sc=app.querySelector('.content-scroll');return sc?sc.scrollTop:0;}
function cancelPendingSearch(){if(!S.loading)return;generation++;S.loading=false;S.toast='';}
function navigate(root){if(!ROOTS.includes(root))return;
 if(S.profileId){back();return;}
 cancelPendingSearch();S.dialog=null;
 if(S.mode==='grid'&&root!=='discover')S.gridHidden=[];
 S.root=root;lastScroll=0;render({resetScroll:true});
}
function changeMode(m){if(!MODES.includes(m))return;
 if(S.mode==='grid'&&m!=='grid')S.gridHidden=[];
 S.mode=m;S.dialog=null;lastScroll=0;render({resetScroll:true});
}
function openProfile(id){if(!byId(id))return;
 S.profileOrigin={root:S.root,mode:S.mode,scroll:saveScroll(),selectedMap:S.selectedMap,deckIndex:S.deckIndex};
 S.profileId=id;S.dialog=null;S.sectionOpen=[];lastScroll=0;
 try{history.pushState({ladiesProfile:true},'',location.href);}catch{}
 render({resetScroll:true});
}
function closeProfile(discard=false){
 if(!discard&&S.profileId&&noteDirty(S.profileId)){withDialog('discard-note');return;}
 const o=S.profileOrigin;S.profileId=null;S.root=o?.root||'discover';S.mode=o?.mode||'swipe';
 S.selectedMap=o?.selectedMap||'v';S.deckIndex=o?.deckIndex??0;
 const v=o?.scroll||0;S.profileOrigin=null;S.dialog=null;lastScroll=v;
 render({scrollTo:v});
}
function back(){if(S.dialog){closeDialog();return;}
 if(S.profileId){closeProfile();return;}
 if(S.root!=='discover'){navigate('discover');return;}
 if(S.mode!=='swipe')changeMode('swipe');
}
function toggleFavorite(id){if(!byId(id))return;S.favorites=isFav(id)?S.favorites.filter(x=>x!==id):[...S.favorites,id];render();}
function decide(action){let p=currentDeck();if(!p)return;const prev={decisions:{...S.decisions},skipped:[...S.skipped],deckIndex:S.deckIndex,photos:{...S.photoIndices},sections:[...S.sectionOpen],scroll:saveScroll(),id:p.id};
 if(action==='like')S.decisions[p.id]='like';else if(action==='dislike')S.decisions[p.id]='dislike';else if(action==='skip'&&!S.skipped.includes(p.id))S.skipped.push(p.id);
 S.undoStack.push(prev);S.deckIndex++;S.sectionOpen=[];lastScroll=0;render({resetScroll:true});}
function undo(){let u=S.undoStack.pop();if(!u)return;S.decisions=u.decisions;S.skipped=u.skipped;S.deckIndex=u.deckIndex;S.photoIndices=u.photos;S.sectionOpen=u.sections;lastScroll=u.scroll;render({scrollTo:u.scroll});}
function resetDeck(){S.deckOrder=results().filter(id=>S.settings.showLikes||S.decisions[id]!=='like');S.deckIndex=0;S.skipped=[];S.undoStack=[];S.gridHidden=[];S.dialog=null;S.sectionOpen=[];lastScroll=0;render({resetScroll:true});}
function likeGrid(id){S.decisions[id]='like';S.gridHidden=[...new Set([...S.gridHidden,id])];render();notify('Gefällt · Profil im aktuellen Grid ausgeblendet');}
function gallery(id,direction){let p=byId(id);if(!p)return;let i=S.photoIndices[id]||0;let next=Math.max(0,Math.min(p.photos.length-1,i+direction));S.photoIndices[id]=next;render();}
function filterResults(f){let list=PROFILES.filter(p=>p.age>=f.ageMin&&p.age<=f.ageMax&&(p.distanceKm<=f.radius||f.place.toLocaleLowerCase('de').includes('markkleeberg')&&p.place==='Markkleeberg'));
 if(f.text){let t=f.text.toLocaleLowerCase('de');list=list.filter(p=>(p.name+' '+p.place+' '+p.about).toLocaleLowerCase('de').includes(t));}
 if(f.place.toLocaleLowerCase('de').includes('markkleeberg'))list=list.filter(p=>p.place==='Markkleeberg');
 if(f.availability==='Jetzt')list=list.filter(p=>['v','s'].includes(p.id));else if(f.availability==='Heute')list=list.filter(p=>['v','s','m','e'].includes(p.id));
 if(f.category!=='Alle')list=list.filter(p=>p.services.includes(f.category));
 if(f.meeting==='Markkleeberg')list=list.filter(p=>p.place==='Markkleeberg');
 if(f.feature==='165–170 cm')list=list.filter(p=>p.height>=165&&p.height<=170);
 if(f.feature==='170 cm und größer')list=list.filter(p=>p.height>=170);
 if(f.service)list=list.filter(p=>p.services.includes(f.service));
 if(f.hideLikes)list=list.filter(p=>!S.decisions[p.id]);
 return list.map(p=>p.id);
}
function runSearch(source){
 if(S.loading)return;
 S.dialog=null;S.loading=true;
 const snapshot=structuredClone(source||S.filters);
 const token=++generation;S.viewState='normal';render();
 setTimeout(()=>{
  if(token!==generation||!S.loading)return;
  S.activeSearch=structuredClone(snapshot);
  S.results=filterResults(snapshot);S.filters=structuredClone(snapshot);S.loading=false;
  S.deckIndex=0;S.deckOrder=S.results.filter(id=>S.settings.showLikes||S.decisions[id]!=='like');
  S.undoStack=[];S.gridHidden=[];S.root='discover';S.mode='swipe';S.sectionOpen=[];
  lastScroll=0;render({resetScroll:true});
  if(!S.results.length)notify('Keine passenden Profile gefunden');
 },480);
}
function downloadable(filename,content,type){const blob=new Blob([content],{type});const url=URL.createObjectURL(blob);let a=document.createElement('a');a.href=url;a.download=filename;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),2500);}
function serializeEditor(){
 const f=app.querySelector('#recordEditor');
 return f?JSON.stringify([...new FormData(f)]):null;
}
function withDialog(type,args={}){
 const b=document.activeElement?.closest('[data-action]');
 dialogReturnFocus=b?{action:b.dataset.action,value:b.dataset.value,id:b.dataset.id}:null;
 S.dialog={type,...args};render({focusDialog:true});
 if(type==='encounter-editor'||type==='price-editor')S.dialog.initial=serializeEditor();
}
function restoreRecord(){
 if(!pendingEditor)return;
 const {dialog,values}=pendingEditor;
 S.dialog=dialog;render({focusDialog:true});
 const f=app.querySelector('#recordEditor');
 for(const [key,value] of values){if(f?.elements.namedItem(key))f.elements.namedItem(key).value=value;}
 pendingEditor=null;
}
function closeDialog(){
 if(S.dialog?.type==='discard-record'){restoreRecord();return;}
 if(['encounter-editor','price-editor'].includes(S.dialog?.type)){
  const values=[...new FormData(app.querySelector('#recordEditor'))];
  if(JSON.stringify(values)!==S.dialog.initial){
   pendingEditor={dialog:S.dialog,values};
   S.dialog={type:'discard-record'};
   render({focusDialog:true});return;
  }
 }
 S.dialog=null;render();
 if(dialogReturnFocus){
  const {action,value,id}=dialogReturnFocus;
  [...app.querySelectorAll('[data-action]')].find(e=>e.dataset.action===action&&e.dataset.value===value&&e.dataset.id===id)?.focus({preventScroll:true});
 }
 dialogReturnFocus=null;
}
const actions={
 'root':(b)=>navigate(b.dataset.value),'mode':b=>changeMode(b.dataset.value),
 'header-search':()=>navigate('search'),'back':()=>back(),'menu':()=>withDialog('menu'),
 'favorite':b=>toggleFavorite(b.dataset.id||S.profileId),'photo-prev':b=>gallery(b.dataset.id,-1),'photo-next':b=>gallery(b.dataset.id,1),
 'open-profile':b=>openProfile(b.dataset.id),'grid-like':b=>likeGrid(b.dataset.id),
 'like':()=>decide('like'),'dislike':()=>decide('dislike'),'skip':()=>decide('skip'),'undo':()=>undo(),
 'reset-deck':()=>resetDeck(),'quick-place':()=>navigate('search'),'quick-availability':()=>withDialog('availability'),
 'quick-avail-value':b=>{S.filters.availability=b.dataset.value;runSearch(S.filters);},
 'map-favs':()=>{S.mapFavorites=!S.mapFavorites;render();},'select-marker':b=>{S.selectedMap=b.dataset.id;render();},
 'map-cluster':b=>{let i=Number(b.dataset.value);withDialog('cluster',{ids:i===0?['e','a','v']:['s','j']});},
 'cluster-select':b=>{S.selectedMap=b.dataset.id;S.dialog=null;render();},
 'resolve-locations':()=>{S.dialog=null;S.mapResolve.running=true;S.mapResolve.completed=0;let generation=++S.mapResolve.generation;render();setTimeout(()=>{if(generation!==S.mapResolve.generation)return;S.mapResolve.completed=3;render();},230);setTimeout(()=>{if(generation!==S.mapResolve.generation)return;S.mapResolve.running=false;S.mapResolve.completed=8;notify('Standortauflösung abgeschlossen · 8 ungefähre Ortslagen');},650);},
 'cancel-resolve':()=>{S.mapResolve.generation++;S.mapResolve.running=false;notify('Standortauflösung abgebrochen · bereits geprüfte Orte bleiben erhalten');},
 'toggle-section':b=>{let k=b.dataset.value;S.sectionOpen=S.sectionOpen.includes(k)?S.sectionOpen.filter(x=>x!==k):[...S.sectionOpen,k];render();},
 'save-note':b=>{S.notes[b.dataset.id]=noteValue(b.dataset.id);delete S.noteDrafts[b.dataset.id];notify('Notiz gespeichert');},
 'clear-note':b=>withDialog('delete-note',{id:b.dataset.id}),
 'confirm-delete-note':()=>{const id=S.dialog.id;delete S.notes[id];delete S.noteDrafts[id];S.dialog=null;notify('Notiz gelöscht');},
 'discard-note':()=>{delete S.noteDrafts[S.profileId];S.dialog=null;closeProfile(true);},
 'new-encounter':b=>withDialog('encounter-editor',{id:b.dataset.id}),
 'edit-encounter':b=>withDialog('encounter-editor',{id:b.dataset.id,record:Number(b.dataset.record)}),
 'new-price':b=>withDialog('price-editor',{id:b.dataset.id}),
 'edit-price':b=>withDialog('price-editor',{id:b.dataset.id,record:Number(b.dataset.record)}),
 'resume-record':()=>restoreRecord(),
 'discard-record':()=>{pendingEditor=null;S.dialog=null;notify('Ungespeicherte Angaben verworfen');},
 'save-record':()=>{
  const d=S.dialog,form=app.querySelector('#recordEditor');if(!form?.reportValidity())return;
  const fd=new FormData(form),encounter=d.type==='encounter-editor';
  const source=encounter?S.encounters:S.priceLists;const entries=source[d.id]||[];
  const payload={id:d.record||S.nextRecordId++,date:String(fd.get('date'))};
  if(encounter){
   payload.duration=String(fd.get('duration'));payload.base=String(fd.get('base'));
   payload.total=String(fd.get('total'));payload.rating=String(fd.get('rating'));
   payload.sympathy=String(fd.get('sympathy'));payload.services=String(fd.get('services'));
   payload.note=String(fd.get('encounterNote'));
  }else{
   payload.items=String(fd.get('items')).trim();if(!payload.items)return;
  }
  if(d.record){source[d.id]=entries.map(r=>r.id===d.record?payload:r);}else source[d.id]=[payload,...entries];
  S.dialog=null;notify(encounter?'Begegnung lokal gespeichert':'Preisliste lokal gespeichert');
 },
 'delete-record':()=>{const d=S.dialog,source=d.type==='encounter-editor'?S.encounters:S.priceLists;
   source[d.id]=(source[d.id]||[]).filter(r=>r.id!==d.record);S.dialog=null;notify('Eintrag lokal gelöscht');},
 'open-result':b=>openProfile(b.dataset.id),
 'last-results':()=>withDialog('last-results'),
 'cancel-search':()=>{cancelPendingSearch();notify('Suche abgebrochen · bisheriger Bestand bleibt erhalten');},
 'contact':b=>withDialog('contact',{label:b.dataset.value}),'close-dialog':()=>closeDialog(),
 'change-place':()=>withDialog('place'),'save-place':()=>{let v=app.querySelector('#modalPlace')?.value.trim();if(!v){notify('Bitte einen Ort eingeben');return;}S.filters.place=v;S.dialog=null;render();},
 'radius':b=>{S.filters.radius=+b.dataset.value;S.dialog=null;render();},'radius-more':()=>withDialog('radius'),
 'filter-avail':b=>{S.filters.availability=b.dataset.value;render();},
 'expand-filter':b=>{let v=b.dataset.value;S.searchExpanded=S.searchExpanded.includes(v)?S.searchExpanded.filter(x=>x!==v):[...S.searchExpanded,v];render();},
 'reset-filters':()=>{S.filters=initFilters();S.previewCount='unbekannt';render();},
 'search':()=>runSearch(),'saved-tab':b=>{S.savedTab=b.dataset.value;render();},
 'saved-options':()=>withDialog('saved-options'),'sort-saved':()=>{S.savedSort=S.savedSort==='reverse'?'name':'reverse';S.dialog=null;render();notify(S.savedSort==='name'?'Nach Namen sortiert':'Reihenfolge umgekehrt');},
 'check-status':()=>{S.dialog=null;notify('Statusprüfung benötigt eine Datenverbindung');},
 'toggle-saved-search':()=>{S.savedSearchOpen=!S.savedSearchOpen;render();},
 'saved-searches':()=>withDialog('saved-searches'),'create-saved':()=>withDialog('name-saved',{mode:'new',value:`${S.filters.place} · ${S.filters.radius} km`}),
 'run-saved':b=>{let q=S.savedSearches.find(x=>x.id===b.dataset.id);if(q)runSearch(q.filters);},
 'edit-saved':b=>{let q=S.savedSearches.find(x=>x.id===b.dataset.id);if(q){S.filters=structuredClone(q.filters);S.dialog=null;navigate('search');notify('Gespeicherte Filter geladen · noch nicht ausgeführt');}},
 'rename-saved':b=>{let q=S.savedSearches.find(x=>x.id===b.dataset.id);if(q)withDialog('name-saved',{mode:'rename',id:q.id,value:q.name});},
 'save-saved-name':()=>{let n=app.querySelector('#savedName')?.value.trim();if(!n){notify('Bitte einen Namen eingeben');return;}let d=S.dialog;if(d.mode==='new')S.savedSearches.push({id:'saved-'+Date.now(),name:n,filters:structuredClone(S.filters),unread:0});else{let q=S.savedSearches.find(x=>x.id===d.id);if(q)q.name=n;}S.dialog={type:'saved-searches'};S.savedSearchOpen=true;render();notify('Gespeicherte Suche aktualisiert');},
 'ack-saved':b=>{let q=S.savedSearches.find(x=>x.id===b.dataset.id);if(!q||!q.unread)return; q.unread=0;render();notify('Neuigkeiten ausdrücklich quittiert');},
 'delete-saved':b=>withDialog('delete-saved',{id:b.dataset.id}),
 'confirm-delete-saved':()=>{S.savedSearches=S.savedSearches.filter(x=>x.id!==S.dialog.id);S.dialog=null;render();},
 'setting-toggle':b=>{let k=b.dataset.value;S.settings[k]=!S.settings[k];if(k==='showLikes'){S.deckOrder=results().filter(id=>S.settings.showLikes||S.decisions[id]!=='like');S.deckIndex=0;S.undoStack=[];}render();},
 'capture-mode':b=>{S.settings.capture=b.dataset.value;render();},
 'clear-capture':()=>{S.settings.capture='Aus';S.settings.captureEntries=0;notify('Sitzung gelöscht und Aufzeichnung ausgeschaltet');},
 'refresh-status':()=>{S.settings.dbStatus='prüft…';render();setTimeout(()=>{S.settings.dbStatus='nicht verbunden';notify('Keine Datenbank angebunden');},500);},
 'save-log':()=>{downloadable('ladies-reference-log.txt','Ladies Explorer Designreferenz – Beispielprotokoll, keine echten Messwerte\nBeispielprofile: '+PROFILES.length+'\nDebug: '+S.settings.debug+'\nHTTP-Modus: '+S.settings.capture+'\n','text/plain;charset=utf-8');notify('Beispielprotokoll gespeichert');},
 'share-log':()=>{withDialog('info',{message:'Externes Teilen ist in dieser Designreferenz nicht verbunden. „Log speichern“ erstellt nur ein Beispielprotokoll.'});},
 'root-settings':()=>navigate('settings'),'dismiss-toast':()=>{S.toast='';render();},
 'retry':()=>{S.viewState='normal';S.dialog=null;render({resetScroll:true});},

};
let suppressGalleryUntil=0;
app.addEventListener('click',event=>{
 const b=event.target.closest('button[data-action]');if(!b||b.disabled)return;
 if(['photo-prev','photo-next'].includes(b.dataset.action)&&performance.now()<suppressGalleryUntil){event.preventDefault();return;}
 const f=actions[b.dataset.action];if(f)f(b);
});
app.addEventListener('input',event=>{let t=event.target;if(t.matches('.text-input')&&t.name==='text')S.filters.text=t.value;
 if(t.name==='profileNote'&&S.profileId){S.noteDrafts[S.profileId]=t.value;}
 if(['ageMin','ageMax'].includes(t.name)){S.filters[t.name]=+t.value;let f=S.filters;if(f.ageMin>f.ageMax){if(t.name==='ageMin')f.ageMax=f.ageMin;else f.ageMin=f.ageMax;}const d=app.querySelector('.filter-age')?.previousElementSibling?.querySelector('span:last-child');if(d)d.textContent=`${f.ageMin}–${f.ageMax} Jahre`;}
});
app.addEventListener('change',event=>{let t=event.target;let allowed=['category','meeting','onlyPhotos','hideLikes','feature','service','maxPages'];if(allowed.includes(t.name)){S.filters[t.name]=t.type==='checkbox'?t.checked:t.type==='number'?Math.max(1,Math.min(50,+t.value||1)):t.value;render();}});
document.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();back();return;}
 const modal=app.querySelector('[role=dialog]');
 if(e.key==='Tab'&&modal){const focusable=[...modal.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled)')];if(focusable.length){let first=focusable[0],last=focusable.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}return;}
if(e.key==='ArrowLeft'||e.key==='ArrowRight'){const focused=document.activeElement;if(focused?.closest('.profile-hero')){let id=focused.closest('.profile-hero').dataset.hero;gallery(id,e.key==='ArrowLeft'?-1:1);}}});
window.addEventListener('popstate',()=>{if(S.profileId)back();});
let pointer=null;
app.addEventListener('pointerdown',e=>{
 const h=e.target.closest('.profile-hero.swipe');
 if(!h||S.profileId||!e.isPrimary||e.target.closest('button:not(.hero-gallery-tap)'))return;
 pointer={x:e.clientX,y:e.clientY,id:h.dataset.hero,t:performance.now(),width:h.clientWidth};
});
app.addEventListener('pointerup',e=>{
 if(!pointer)return;const o=pointer;pointer=null;
 const dx=e.clientX-o.x,dy=e.clientY-o.y;
 if(Math.abs(dx)>14||Math.abs(dy)>14)suppressGalleryUntil=performance.now()+450;
 if(o.id!==currentDeck()?.id||performance.now()-o.t>800||
    Math.abs(dx)<Math.max(72,o.width*.25)||Math.abs(dx)<Math.abs(dy)*1.3)return;
 decide(dx>0?'like':'dislike');
});
app.addEventListener('pointercancel',()=>{pointer=null;});
// Der Einstieg zeigt immer den normalen Entdecken-Screen. Keine Test-URL-Parameter.
render({resetScroll:true});
