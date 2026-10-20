-- Android integration, run once over RPC after startup (and after any user init.lua).
local channel, home, notes, documents = ...
-- Compare resolved paths: Android's files directory is reached through a symlink.
home, documents = vim.fn.resolve(home), vim.fn.resolve(documents)
local group = vim.api.nvim_create_augroup('android', { clear = true })
-- App defaults, except where the user's configuration already chose a value.
local function default(name, value)
  if not vim.api.nvim_get_option_info2(name, {}).was_set then vim.o[name] = value end
end
default('termguicolors', true)
default('mouse', 'a')
default('number', true)
-- Narrow screens: wrap long lines at word boundaries and keep their indent.
default('linebreak', true)
default('breakindent', true)
default('undofile', true)
vim.opt.swapfile = false
vim.opt.shortmess:append('I')
-- Relative paths such as `:e todo.txt` resolve inside app storage instead of `/`.
vim.cmd.cd(vim.fn.fnameescape(home))
if vim.fn.filereadable(vim.env.VIMRUNTIME .. '/doc/tags') == 0 then
  vim.cmd.helptags(vim.fn.fnameescape(vim.env.VIMRUNTIME .. '/doc'))
end
vim.cmd('filetype plugin indent on')
vim.cmd('syntax enable')

-- The + and * registers use Android's clipboard through the UI channel; no clipboard tool runs.
-- Text crosses as one string, so the number of lines is not limited by the RPC message size.
local clipboard_limit = 512 * 1024
local function copy(lines, regtype)
  local text = table.concat(lines, '\n')
  if #text > clipboard_limit then
    vim.notify('Selection exceeds the 512 KiB Android clipboard limit', vim.log.levels.WARN)
    return
  end
  vim.rpcnotify(channel, 'android_clipboard_set', text, regtype)
end
local function paste()
  local text, regtype = unpack(vim.rpcrequest(channel, 'android_clipboard_get'))
  return { vim.split(text, '\n', { plain = true }), regtype }
end
if vim.g.clipboard == nil then
  vim.g.clipboard = { name = 'android', copy = { ['+'] = copy, ['*'] = copy },
                      paste = { ['+'] = paste, ['*'] = paste } }
end

-- Imported documents are local copies; report saves so Android can update the original.
vim.api.nvim_create_autocmd('BufWritePost', { group = group, callback = function(event)
  local path = vim.fn.resolve(vim.fn.fnamemodify(event.match, ':p'))
  if vim.startswith(path, documents .. '/') then vim.rpcnotify(channel, 'android_document_written', path) end
end })

_G.AndroidNeovim = {
  -- Android may end a background process without notice: save named file buffers and history.
  background = function()
    if vim.g.android_autosave == false then return end
    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
      local bo = vim.bo[buf]
      if bo.modified and bo.buftype == '' and bo.modifiable and not bo.readonly
          and vim.api.nvim_buf_get_name(buf) ~= '' then
        pcall(vim.api.nvim_buf_call, buf, function() vim.cmd('silent keepalt keepjumps update') end)
      end
    end
    pcall(vim.cmd.wshada)
  end,
  theme = function(dark)
    local background = dark and 'dark' or 'light'
    if vim.g.android_follow_system_theme ~= false and vim.o.background ~= background then
      vim.o.background = background
    end
  end,
}

-- Reopen the most recent file kept in app storage, falling back to the welcome notes.
local start = notes
for _, file in ipairs(vim.v.oldfiles) do
  local resolved = vim.fn.resolve(file)
  if vim.startswith(resolved, home .. '/') and not vim.startswith(resolved, vim.fn.resolve(vim.env.VIMRUNTIME) .. '/')
      and vim.fn.filereadable(file) == 1 then
    start = file
    break
  end
end
vim.cmd.edit(vim.fn.fnameescape(start))
