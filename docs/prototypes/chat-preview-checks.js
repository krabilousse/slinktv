document.querySelectorAll('*').forEach(e=>e.style.transition='none');
paused=true;
let passed=0;
function check(ok,label){if(!ok)throw new Error('FAIL: '+label);passed++}
function key(value){document.dispatchEvent(new KeyboardEvent('keydown',{key:value,bubbles:true,cancelable:true}))}
check(mode==='docked','initial dock');
const ratio=()=>{const r=document.querySelector('.picture').getBoundingClientRect();return r.width/r.height};
check(Math.abs(ratio()-16/9)<.001,'docked ratio');
$('toggle').focus();key('ArrowRight');check(document.activeElement.id==='settingsButton','remote right focus');
$('settingsButton').click();check(!scrim.hidden,'dialog opens');
key('ArrowRight');check(document.activeElement.dataset.mode==='overlay','dialog right focus');
document.activeElement.click();check(mode==='overlay','overlay selection');
$('opacity').value=20;$('opacity').dispatchEvent(new Event('input'));check(stage.style.getPropertyValue('--opacity')==='0.2','opacity independent');
check(Math.abs(ratio()-16/9)<.001,'overlay ratio');
key('Escape');check(scrim.hidden&&document.activeElement.id==='settingsButton','escape restores focus');
key('c');check(mode==='hidden','shortcut hides');
key('c');check(mode==='overlay','shortcut restores placement');
$('burst').click();check(messages.length===24&&$('messages').children.length===24,'bounded burst');
$('moderate').click();check(messages.length===23,'deletion');
$('clearChat').click();check(messages.length===1&&messages[0].system,'clear');
messages=[];for(let i=0;i<12;i++)add(i);render();
const base=document.querySelector('.emote:has(.layer)');check(base&&base.querySelectorAll('img').length===2,'zero width composite');
check(document.querySelectorAll('img[src^="http"]').length===0,'embedded assets');
key('Escape');check(controls.inert,'hidden controls not focusable');
key('Enter');check(!controls.inert&&document.activeElement.id==='play','OK reveals controls');
setMode('overlay');$('opacity').value=40;$('opacity').dispatchEvent(new Event('input'));$('toast').hidden=true;
console.log('PASS '+passed+' chat prototype assertions');
