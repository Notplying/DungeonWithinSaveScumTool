' Double-click launcher for DungeonWithin Save Manager.
' Runs pythonw (windowless), so no console window lingers in the background.
Option Explicit

Dim sh, fso, dir, target
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
target = dir & "\save_manager.py"
sh.CurrentDirectory = dir

On Error Resume Next
Err.Clear
sh.Run "pythonw """ & target & """", 0, False
If Err.Number <> 0 Then
  Err.Clear
  sh.Run "pyw """ & target & """", 0, False
End If
