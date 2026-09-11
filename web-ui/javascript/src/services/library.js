/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {handleJsonOrError} from "../utils/fetch";
import {parseJson} from "../utils/json";

export const fetchLibraryInfos = () => {
    return fetch('/api/library/infos')
        .then(handleJsonOrError);
};

export const fetchLibraryPacks = () => {
    return fetch('/api/library/packs')
        .then(handleJsonOrError);
};

export const downloadFromLibrary = async (uuid, path) => {
    return await fetch('/api/library/download', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({uuid, path})
    });
};

export const uploadToLibrary = async (uuid, path, packData, progressHandler) => {
    return new Promise((resolve, reject) => {
        let xhr = new XMLHttpRequest();
        if (xhr.upload) {
            xhr.upload.onprogress = progressHandler;
        }
        xhr.onload = () => {
            let response = parseJson(xhr.responseText);
            if (response === undefined) {
                reject(new Error('Upload failed: unexpected response from the server (' + xhr.status + ')'));
                return;
            }
            console.log('xhr upload complete');
            resolve(response);
        };
        xhr.open('post', '/api/library/upload', true);
        let formData = new FormData();
        formData.append("uuid", uuid);
        formData.append("path", path);
        formData.append("pack", packData);
        xhr.send(formData);
    });
};

export const convertInLibrary = async (uuid, path, format, allowEnriched) => {
    return await fetch('/api/library/convert', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({uuid, path, format, allowEnriched})
    })
        .then(handleJsonOrError);
};

export const removeFromLibrary = (path) => {
    return fetch('/api/library/remove', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({path})
    })
        .then(handleJsonOrError);
};

/*
 * Whether a converted pack still corresponds to the source it was made from.
 *
 * Asked only when a pack is dropped on a device, never while listing: the backend reads both
 * artefacts in full to answer. It returns a verdict and nothing else — no digest, no record — so
 * this side has nothing to decide and nothing to get wrong.
 */
export const verifyConversion = async (sourcePath, convertedPath) => {
    return await fetch('/api/library/verify-conversion', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({sourcePath, convertedPath})
    })
        .then(handleJsonOrError);
};
