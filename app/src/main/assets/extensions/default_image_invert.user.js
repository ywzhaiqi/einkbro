// ==UserScript==
// @name        图片反色
// @namespace   einkbro
// @version     1.0.0
// @description Invert all images on the page
// ==/UserScript==

// 需手动点击
var images = document.getElementsByTagName('img');
for (var i = 0; i < images.length; i++) {
    images[i].style.filter = 'invert(100%)';
}