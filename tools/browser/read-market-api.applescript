on run argv
  set readMode to "list"
  if (count argv) > 0 then set readMode to item 1 of argv
  set tabSelector to ""
  if (count argv) > 1 then set tabSelector to item 2 of argv
  set foundTabs to ""
  tell application "Google Chrome"
    repeat with w in windows
      repeat with t in tabs of w
        set tabUrl to URL of t
        if (tabUrl contains tabSelector) and (tabUrl starts with "https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall" or tabUrl starts with "http://openapi.11st.co.kr/openapi/OpenApiGuide.tmall" or tabUrl starts with "https://developers.cafe24.com/docs/" or tabUrl starts with "https://apidocs.cafe24.com/docs/" or tabUrl starts with "https://apidocs.cafe24.com/reference/" or tabUrl starts with "https://apicenter.commerce.naver.com/docs/" or tabUrl starts with "https://developers.coupang.com/") then
          if readMode is "navigate" then
            if (count argv) < 3 then error "DOCUMENT_URL_REQUIRED"
            set documentUrl to item 3 of argv
            if not (documentUrl starts with "https://apidocs.cafe24.com/docs/admin/" or documentUrl starts with "https://apidocs.cafe24.com/docs/guide/") then error "ONLY_CAFE24_DOCUMENT_URLS_ALLOWED"
            set URL of t to documentUrl
            return "DOCUMENT_NAVIGATING"
          end if
          if readMode is "properties" and (tabUrl starts with "https://apidocs.cafe24.com/docs/admin/") then
            return execute t javascript "(()=>{const tab=Array.from(document.querySelectorAll('[role=tab],button')).find(e=>e.textContent.trim()==='Properties');if(!tab)return 'PROPERTIES_TAB_NOT_FOUND';tab.click();return 'PROPERTIES_OPENED';})()"
          end if
          if readMode is "read" then
            return execute t javascript "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,65000),links:Array.from(document.links).filter(a=>a.innerText.trim()).map(a=>({text:a.innerText.trim(),href:a.href})).slice(0,180)})"
          end if
          set foundTabs to foundTabs & (title of t) & " | " & tabUrl & linefeed
        end if
      end repeat
    end repeat
  end tell
  if foundTabs is "" then return "API_DOCUMENT_TAB_NOT_OPEN"
  return foundTabs
end run
