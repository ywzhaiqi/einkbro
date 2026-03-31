// ==UserScript==
// @name        图片点击反色
// @namespace   einkbro
// @match       *://*/*
// @version     1.0.0
// @description 点击图片反色
// ==/UserScript==

function initInvertWithCSS(imgElement) {
    let isInverted = false;
    
    imgElement.addEventListener('click', function() {
        if (!isInverted) {
            imgElement.style.filter = 'invert(100%)';
            isInverted = true;
        } else {
            imgElement.style.filter = 'none';
            isInverted = false;
        }
    });
}

function initMultipleImages(selector) {
    const images = document.querySelectorAll(selector);
    images.forEach(img => {
        if (img.complete) {
            initInvertWithCSS(img);
        } else {
            img.addEventListener('load', () => initInvertWithCSS(img));
        }
    });
}

initMultipleImages('img');